// Runs the real migrations on an embedded Postgres (no Supabase project needed)
// with small stand-ins for Supabase's auth and storage schemas.
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { PGlite, type Transaction } from "@electric-sql/pglite";
import { pg_trgm } from "@electric-sql/pglite/contrib/pg_trgm";
import { beforeAll, describe, expect, it } from "vitest";

const MIGRATIONS = join(__dirname, "..", "..", "supabase", "migrations");

const SUPABASE_STUBS = `
  create role anon nologin;
  create role authenticated nologin;
  create schema auth;
  create table auth.users (
    id uuid primary key,
    email text,
    raw_user_meta_data jsonb default '{}'::jsonb
  );
  create function auth.uid() returns uuid language sql stable as $$
    select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
  $$;
  create schema storage;
  create table storage.buckets (
    id text primary key, name text, public boolean,
    file_size_limit bigint, allowed_mime_types text[]
  );
  create table storage.objects (
    id uuid primary key default gen_random_uuid(),
    bucket_id text references storage.buckets (id),
    name text,
    unique (bucket_id, name)
  );
  alter table storage.objects enable row level security;
  create function storage.foldername(name text) returns text[] language sql immutable as $$
    select (string_to_array(name, '/'))[1 : array_length(string_to_array(name, '/'), 1) - 1]
  $$;
  grant usage on schema auth, storage, public to anon, authenticated;
  grant execute on function auth.uid() to anon, authenticated;
  grant execute on function storage.foldername(text) to anon, authenticated;
  grant all on storage.objects to anon, authenticated;
  -- Supabase grants these by default on everything created in public.
  alter default privileges in schema public grant all on tables to anon, authenticated;
  alter default privileges in schema public grant all on sequences to anon, authenticated;
  alter default privileges in schema public grant execute on functions to anon, authenticated;
`;

const ADMIN = "00000000-0000-4000-8000-00000000000a";
const REVIEWER = "00000000-0000-4000-8000-00000000000b";
const OPERATOR_A = "00000000-0000-4000-8000-00000000000c";
const OPERATOR_B = "00000000-0000-4000-8000-00000000000d";
const PENDING = "00000000-0000-4000-8000-00000000000e";
const DISABLED = "00000000-0000-4000-8000-00000000000f";

const DEVICE_A = "10000000-0000-4000-8000-00000000000a";
const DEVICE_B = "10000000-0000-4000-8000-00000000000b";
const SESSION_A = "20000000-0000-4000-8000-00000000000a";
const SESSION_B = "20000000-0000-4000-8000-00000000000b";
const VIOLATION_A = "30000000-0000-4000-8000-00000000000a";
const VIOLATION_B = "30000000-0000-4000-8000-00000000000b";

let db: PGlite;

function as<T>(uid: string | null, fn: (tx: Transaction) => Promise<T>): Promise<T> {
  return db.transaction(async (tx) => {
    await tx.query("select set_config('request.jwt.claim.sub', $1, true)", [uid ?? ""]);
    await tx.exec(`set local role ${uid ? "authenticated" : "anon"}`);
    return fn(tx);
  }) as Promise<T>;
}

function payload(id: string, session: string, device: string, uid: string, extra: object = {}) {
  return JSON.stringify({
    id,
    session_id: session,
    device_id: device,
    track_id: 12,
    source: "live",
    detected_at: "2026-11-09T05:00:00Z",
    rider_count: 2,
    no_helmet_count: 1,
    helmet_confidence: 0.84,
    motorcycle_confidence: 0.88,
    frames_observed: 20,
    no_helmet_frames: 9,
    plate_text_ocr: "LEA1234",
    plate_ocr_confidence: 0.91,
    plate_ocr_status: "read",
    evidence: [
      {
        id: id.replace(/^3/, "4"),
        kind: "full_frame",
        seq: 0,
        storage_path: `${uid}/${id}/full_frame_0.jpg`,
        bytes: 300000,
      },
    ],
    ...extra,
  });
}

async function sync(uid: string, body: string) {
  return as(uid, async (tx) => {
    const r = await tx.query<{ r: { inserted: boolean } }>("select public.sync_violation($1::jsonb) as r", [body]);
    return r.rows[0].r;
  });
}

async function count(uid: string | null, table: string) {
  return as(uid, async (tx) => {
    const r = await tx.query<{ n: number }>(`select count(*)::int as n from ${table}`);
    return r.rows[0].n;
  });
}

beforeAll(async () => {
  db = new PGlite({ extensions: { pg_trgm } });
  await db.exec(SUPABASE_STUBS);
  for (const file of readdirSync(MIGRATIONS).sort()) {
    await db.exec(readFileSync(join(MIGRATIONS, file), "utf8"));
  }

  const users: [string, string][] = [
    [ADMIN, "admin@test.local"],
    [REVIEWER, "reviewer@test.local"],
    [OPERATOR_A, "a@test.local"],
    [OPERATOR_B, "b@test.local"],
    [PENDING, "pending@test.local"],
    [DISABLED, "disabled@test.local"],
  ];
  for (const [id, email] of users) {
    await db.query(`insert into auth.users (id, email, raw_user_meta_data) values ($1, $2, '{"full_name":"T"}')`, [id, email]);
  }

  // The same statement the plan uses once in the SQL editor for the first admin.
  await db.query(`update public.profiles set role = 'admin', status = 'active' where id = $1`, [ADMIN]);
  await db.query(`update public.profiles set role = 'reviewer', status = 'active' where id = $1`, [REVIEWER]);
  await db.query(`update public.profiles set status = 'active' where id = any($1::uuid[])`, [[OPERATOR_A, OPERATOR_B]]);
  await db.query(`update public.profiles set status = 'disabled' where id = $1`, [DISABLED]);

  for (const [uid, device, session] of [
    [OPERATOR_A, DEVICE_A, SESSION_A],
    [OPERATOR_B, DEVICE_B, SESSION_B],
  ]) {
    await as(uid, async (tx) => {
      await tx.query("insert into public.devices (id, owner_id, label) values ($1, $2, 'phone')", [device, uid]);
      await tx.query(
        `insert into public.detection_sessions
           (id, device_id, operator_id, source, started_at, location_label,
            motorcycles_total, riders_total, riders_helmet, riders_no_helmet)
         values ($1, $2, $3, 'live', '2026-11-09T04:00:00Z', 'Mall Road', 10, 12, 9, 3)`,
        [session, device, uid],
      );
    });
  }

  await sync(OPERATOR_A, payload(VIOLATION_A, SESSION_A, DEVICE_A, OPERATOR_A));
  await sync(OPERATOR_B, payload(VIOLATION_B, SESSION_B, DEVICE_B, OPERATOR_B, { plate_text_ocr: "ABC987" }));
});

describe("accounts", () => {
  it("creates a pending operator on sign-up", async () => {
    const r = await db.query<{ role: string; status: string }>("select role, status from public.profiles where id = $1", [PENDING]);
    expect(r.rows[0]).toEqual({ role: "operator", status: "pending" });
  });

  it("stops an operator making themselves admin", async () => {
    await expect(
      as(OPERATOR_A, (tx) => tx.query("update public.profiles set role = 'admin' where id = $1", [OPERATOR_A])),
    ).rejects.toThrow(/only an admin/);
  });

  it("lets a user change their own name only", async () => {
    await as(OPERATOR_A, (tx) => tx.query("update public.profiles set full_name = 'New' where id = $1", [OPERATOR_A]));
    const r = await db.query<{ full_name: string }>("select full_name from public.profiles where id = $1", [OPERATOR_A]);
    expect(r.rows[0].full_name).toBe("New");
  });

  it("lets an admin approve and records who approved", async () => {
    const extra = "00000000-0000-4000-8000-000000000011";
    await db.query("insert into auth.users (id, email) values ($1, 'x@test.local')", [extra]);
    await as(ADMIN, (tx) => tx.query("update public.profiles set status = 'active' where id = $1", [extra]));
    const r = await db.query<{ status: string; approved_by: string }>("select status, approved_by from public.profiles where id = $1", [extra]);
    expect(r.rows[0]).toEqual({ status: "active", approved_by: ADMIN });
  });

  it("does not let a reviewer see past their role into user administration", async () => {
    await expect(
      as(REVIEWER, (tx) => tx.query("update public.profiles set status = 'disabled' where id = $1", [REVIEWER])),
    ).rejects.toThrow(/only an admin/);
  });
});

describe("row level security", () => {
  it("shows signed-out visitors nothing", async () => {
    for (const t of ["public.profiles", "public.devices", "public.detection_sessions", "public.violations", "public.violation_evidence", "public.review_events"]) {
      expect(await count(null, t), t).toBe(0);
    }
  });

  it("keeps operators out of each other's data", async () => {
    expect(await count(OPERATOR_A, "public.violations")).toBe(1);
    expect(await count(OPERATOR_B, "public.violations")).toBe(1);
    expect(await count(OPERATOR_A, "public.violation_evidence")).toBe(1);
    expect(await count(OPERATOR_A, "public.devices")).toBe(1);
    expect(await count(OPERATOR_A, "public.detection_sessions")).toBe(1);
    expect(await count(OPERATOR_A, "public.review_events")).toBe(0);
  });

  it("lets reviewers and admins read everything", async () => {
    expect(await count(REVIEWER, "public.violations")).toBe(2);
    expect(await count(ADMIN, "public.violations")).toBe(2);
    expect(await count(REVIEWER, "public.violation_evidence")).toBe(2);
  });

  it("blocks pending users from inserting", async () => {
    await expect(
      as(PENDING, (tx) => tx.query("insert into public.devices (id, owner_id) values (gen_random_uuid(), $1)", [PENDING])),
    ).rejects.toThrow(/row-level security/);
  });

  it("blocks disabled users from reading their old data or inserting", async () => {
    expect(await count(DISABLED, "public.violations")).toBe(0);
    await expect(
      as(DISABLED, (tx) => tx.query("insert into public.devices (id, owner_id) values (gen_random_uuid(), $1)", [DISABLED])),
    ).rejects.toThrow(/row-level security/);
  });

  it("blocks uploading a violation from someone else's device", async () => {
    const id = "30000000-0000-4000-8000-000000000099";
    await expect(sync(OPERATOR_A, payload(id, SESSION_B, DEVICE_B, OPERATOR_A))).rejects.toThrow(/row-level security/);
  });

  it("stops operators reviewing violations", async () => {
    await as(OPERATOR_A, (tx) => tx.query("update public.violations set review_status = 'rejected' where id = $1", [VIOLATION_A]));
    const r = await db.query<{ review_status: string }>("select review_status from public.violations where id = $1", [VIOLATION_A]);
    expect(r.rows[0].review_status).toBe("unreviewed");
  });

  it("only lets admins delete", async () => {
    await as(REVIEWER, (tx) => tx.query("delete from public.violations where id = $1", [VIOLATION_B]));
    expect(await count(REVIEWER, "public.violations")).toBe(2);
  });
});

describe("sync_violation", () => {
  it("ignores a repeat of the same ID, so retries cannot duplicate", async () => {
    const again = await sync(OPERATOR_A, payload(VIOLATION_A, SESSION_A, DEVICE_A, OPERATOR_A));
    expect(again.inserted).toBe(false);
    expect(await count(OPERATOR_A, "public.violations")).toBe(1);
    expect(await count(OPERATOR_A, "public.violation_evidence")).toBe(1);
  });

  it("always stamps the signed-in user as operator", async () => {
    const r = await db.query<{ operator_id: string }>("select operator_id from public.violations where id = $1", [VIOLATION_A]);
    expect(r.rows[0].operator_id).toBe(OPERATOR_A);
  });

  it("requires a timestamp for video violations", async () => {
    const id = "30000000-0000-4000-8000-000000000098";
    await expect(sync(OPERATOR_A, payload(id, SESSION_A, DEVICE_A, OPERATOR_A, { source: "video" }))).rejects.toThrow(/video_needs_timestamp/);
  });
});

describe("review and audit", () => {
  it("never lets a reviewer change the AI's results", async () => {
    await expect(
      as(REVIEWER, (tx) => tx.query("update public.violations set plate_text_ocr = 'FAKE1' where id = $1", [VIOLATION_B])),
    ).rejects.toThrow(/AI results/);
    await expect(
      as(REVIEWER, (tx) => tx.query("update public.violations set helmet_confidence = 0.1 where id = $1", [VIOLATION_B])),
    ).rejects.toThrow(/AI results/);
  });

  it("keeps the OCR text, stores the correction and writes the audit log", async () => {
    await as(REVIEWER, (tx) =>
      tx.query("update public.violations set plate_text_corrected = 'ABC988', review_status = 'confirmed', reviewed_by = $2 where id = $1", [VIOLATION_B, ADMIN]),
    );
    const v = await db.query<Record<string, string>>(
      "select plate_text_ocr, plate_text_corrected, plate_text_final, plate_search, reviewed_by from public.violations where id = $1",
      [VIOLATION_B],
    );
    expect(v.rows[0]).toEqual({
      plate_text_ocr: "ABC987",
      plate_text_corrected: "ABC988",
      plate_text_final: "ABC988",
      plate_search: "ABC988",
      reviewed_by: REVIEWER,
    });
    const e = await db.query<{ action: string; actor_id: string }>(
      "select action, actor_id from public.review_events where violation_id = $1 order by action",
      [VIOLATION_B],
    );
    expect(e.rows).toEqual([
      { action: "status_changed", actor_id: REVIEWER },
      { action: "plate_corrected", actor_id: REVIEWER },
    ]);
  });

  it("finds partial plates", async () => {
    const r = await as(REVIEWER, (tx) =>
      tx.query<{ id: string }>("select id from public.violations where plate_search ilike '%' || $1 || '%'", ["LEA12"]),
    );
    expect(r.rows.map((x) => x.id)).toEqual([VIOLATION_A]);
  });
});

describe("evidence storage", () => {
  const object = (owner: string) => `${owner}/${VIOLATION_A}/plate_0.jpg`;
  const put = (uid: string, name: string) =>
    as(uid, (tx) => tx.query("insert into storage.objects (bucket_id, name) values ('evidence', $1)", [name]));

  it("is a private bucket with the planned limits", async () => {
    const r = await db.query<Record<string, unknown>>("select public, file_size_limit, allowed_mime_types from storage.buckets where id = 'evidence'");
    expect(r.rows[0]).toEqual({ public: false, file_size_limit: 5242880, allowed_mime_types: ["image/jpeg", "image/webp"] });
  });

  it("confines uploads to the uploader's own folder", async () => {
    await put(OPERATOR_A, object(OPERATOR_A));
    await expect(put(OPERATOR_A, object(OPERATOR_B))).rejects.toThrow(/row-level security/);
    await expect(put(PENDING, object(PENDING))).rejects.toThrow(/row-level security/);
  });

  it("hides files from other operators and signed-out visitors", async () => {
    expect(await count(OPERATOR_A, "storage.objects")).toBe(1);
    expect(await count(OPERATOR_B, "storage.objects")).toBe(0);
    expect(await count(null, "storage.objects")).toBe(0);
    expect(await count(REVIEWER, "storage.objects")).toBe(1);
  });

  it("only lets admins delete files", async () => {
    await as(OPERATOR_A, (tx) => tx.query("delete from storage.objects"));
    await as(REVIEWER, (tx) => tx.query("delete from storage.objects"));
    expect(await count(REVIEWER, "storage.objects")).toBe(1);
    await as(ADMIN, (tx) => tx.query("delete from storage.objects"));
    expect(await count(REVIEWER, "storage.objects")).toBe(0);
  });
});

describe("dashboard numbers", () => {
  const FROM = "2026-11-01T00:00:00Z";
  const TO = "2026-12-01T00:00:00Z";

  it("matches hand-counted totals for staff", async () => {
    const r = await as(REVIEWER, (tx) =>
      tx.query<{ s: Record<string, number> }>("select public.dashboard_summary($1, $2) as s", [FROM, TO]),
    );
    expect(r.rows[0].s).toMatchObject({
      sessions: 2,
      motorcycles: 20,
      riders: 24,
      riders_helmet: 18,
      riders_no_helmet: 6,
      compliance_pct: 75,
      ai_alerts: 2,
      unreviewed: 1,
      reviewed_confirmed: 1,
    });
  });

  it("only counts an operator's own data", async () => {
    const r = await as(OPERATOR_A, (tx) =>
      tx.query<{ s: Record<string, number> }>("select public.dashboard_summary($1, $2) as s", [FROM, TO]),
    );
    expect(r.rows[0].s).toMatchObject({ sessions: 1, motorcycles: 10, ai_alerts: 1 });
  });

  it("filters by source and groups trends in Pakistan time", async () => {
    const video = await as(REVIEWER, (tx) =>
      tx.query<{ s: Record<string, number> }>("select public.dashboard_summary($1, $2, 'video') as s", [FROM, TO]),
    );
    expect(video.rows[0].s).toMatchObject({ sessions: 0, ai_alerts: 0 });
    const trend = await as(REVIEWER, (tx) =>
      tx.query<{ total: number }>("select total::int from public.violation_trend($1, $2)", [FROM, TO]),
    );
    expect(trend.rows).toEqual([{ total: 2 }]);
  });

  it("runs every analytics report", async () => {
    const reports = [
      ["compliance_by_location", 2],
      ["ocr_breakdown", 1],
      ["review_outcomes", 2],
    ] as const;
    for (const [fn, rows] of reports) {
      const r = await as(REVIEWER, (tx) => tx.query(`select * from public.${fn}($1, $2)`, [FROM, TO]));
      expect(r.rows.length, fn).toBeGreaterThanOrEqual(rows === 2 ? 1 : rows);
    }
    const hour = await as(REVIEWER, (tx) => tx.query<{ hour_of_day: number }>("select hour_of_day from public.compliance_by_hour($1, $2)", [FROM, TO]));
    expect(hour.rows).toEqual([{ hour_of_day: 9 }]);
    const bySource = await as(REVIEWER, (tx) => tx.query("select * from public.compliance_by_source($1, $2)", [FROM, TO]));
    expect(bySource.rows.length).toBe(1);
  });
});
