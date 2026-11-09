"use client";

import { Bar, BarChart, CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";

const axis = { fontSize: 12, fill: "var(--ink-3)" };
const tooltipStyle = {
  contentStyle: { background: "var(--surface)", border: "1px solid var(--line)", borderRadius: 6, fontSize: 13, color: "var(--ink)" },
  labelStyle: { color: "var(--ink-2)", marginBottom: 2 },
  itemStyle: { color: "var(--ink)", padding: 0 },
};

export interface TrendDatum {
  label: string;
  live: number;
  video: number;
}

// Colour follows the source, so filtering to one never repaints the other.
export function TrendChart({ data, showLive, showVideo }: { data: TrendDatum[]; showLive: boolean; showVideo: boolean }) {
  const both = showLive && showVideo;
  return (
    <div className="h-64 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={data} margin={{ top: 8, right: 12, bottom: 0, left: -18 }}>
          <CartesianGrid vertical={false} stroke="var(--grid)" />
          <XAxis dataKey="label" tick={axis} tickLine={false} axisLine={{ stroke: "var(--line)" }} minTickGap={28} />
          <YAxis allowDecimals={false} tick={axis} tickLine={false} axisLine={false} />
          <Tooltip {...tooltipStyle} cursor={{ stroke: "var(--ink-3)", strokeDasharray: "3 3" }} />
          {both && <Legend iconType="plainline" wrapperStyle={{ fontSize: 13 }} formatter={(value) => <span style={{ color: "var(--ink-2)" }}>{value}</span>} />}
          {showLive && <Line type="monotone" dataKey="live" name="Live camera" stroke="var(--series-live)" strokeWidth={2} dot={false} activeDot={{ r: 4, strokeWidth: 2, stroke: "var(--surface)" }} />}
          {showVideo && <Line type="monotone" dataKey="video" name="Prerecorded video" stroke="var(--series-video)" strokeWidth={2} dot={false} activeDot={{ r: 4, strokeWidth: 2, stroke: "var(--surface)" }} />}
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}

export function HourChart({ data }: { data: { hour: string; compliance: number | null }[] }) {
  return (
    <div className="h-56 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: -18 }} barCategoryGap={2}>
          <CartesianGrid vertical={false} stroke="var(--grid)" />
          <XAxis dataKey="hour" tick={axis} tickLine={false} axisLine={{ stroke: "var(--line)" }} interval={2} />
          <YAxis domain={[0, 100]} unit="%" tick={axis} tickLine={false} axisLine={false} />
          <Tooltip {...tooltipStyle} cursor={{ fill: "var(--surface-2)" }} formatter={(value) => [`${value}%`, "Compliance"]} />
          <Bar dataKey="compliance" fill="var(--series-live)" radius={[4, 4, 0, 0]} maxBarSize={24} isAnimationActive={false} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}

export function SourceComparisonChart({ data }: { data: { source: string; compliance: number | null }[] }) {
  return (
    <div className="h-40 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} layout="vertical" margin={{ top: 0, right: 16, bottom: 0, left: 16 }}>
          <CartesianGrid horizontal={false} stroke="var(--grid)" />
          <XAxis type="number" domain={[0, 100]} unit="%" tick={axis} tickLine={false} axisLine={false} />
          <YAxis type="category" dataKey="source" width={110} tick={axis} tickLine={false} axisLine={{ stroke: "var(--line)" }} />
          <Tooltip {...tooltipStyle} cursor={{ fill: "var(--surface-2)" }} formatter={(value) => [`${value}%`, "Compliance"]} />
          <Bar dataKey="compliance" fill="var(--series-live)" radius={[0, 4, 4, 0]} maxBarSize={22} isAnimationActive={false} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
