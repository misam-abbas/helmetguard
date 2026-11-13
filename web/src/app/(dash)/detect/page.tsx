import type { Metadata } from "next";
import { VideoAnalyzer } from "@/components/VideoAnalyzer";
import { PageHeader } from "@/components/ui";
import { requireStaff } from "@/lib/auth";

export const metadata: Metadata = { title: "Analyse a video" };

export default async function DetectPage() {
  await requireStaff();
  return (
    <>
      <PageHeader title="Analyse a video" subtitle="Finds motorcycles and riders without helmets, using the same models as the Android app" />
      <VideoAnalyzer />
    </>
  );
}
