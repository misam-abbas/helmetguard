import type { ServerClient } from "@/lib/supabase/server";
import type { Evidence } from "@/lib/types";

const SIGNED_URL_SECONDS = 60 * 60;

/** Signed links made with the viewer's own session. There are no public links. */
export async function signPaths(supabase: ServerClient, paths: string[]): Promise<Map<string, string>> {
  const urls = new Map<string, string>();
  if (paths.length === 0) return urls;
  const { data } = await supabase.storage.from("evidence").createSignedUrls(paths, SIGNED_URL_SECONDS);
  for (const item of data ?? []) {
    if (item.path && item.signedUrl) urls.set(item.path, item.signedUrl);
  }
  return urls;
}

/** Lists use the small thumbnails only, to protect the free bandwidth quota. */
export async function thumbnailsFor(supabase: ServerClient, violationIds: string[]): Promise<Map<string, string>> {
  const result = new Map<string, string>();
  if (violationIds.length === 0) return result;
  const { data } = await supabase
    .from("violation_evidence")
    .select("violation_id, storage_path")
    .eq("kind", "thumb")
    .in("violation_id", violationIds);
  const rows = (data ?? []) as Pick<Evidence, "violation_id" | "storage_path">[];
  const urls = await signPaths(
    supabase,
    rows.map((r) => r.storage_path),
  );
  for (const row of rows) {
    const url = urls.get(row.storage_path);
    if (url) result.set(row.violation_id, url);
  }
  return result;
}
