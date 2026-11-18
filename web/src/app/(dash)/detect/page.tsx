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
      <p className="mt-8 max-w-3xl text-xs text-ink-3">
        Models trained with Ultralytics YOLO11 (AGPL-3.0) on helmet-detection-and-license-plate-recognition by on Gia Capital
        (CC BY 4.0, Roboflow Universe), Helmet vs No Helmet by Uzair Ahmad Khan (CC BY 4.0, Roboflow Universe) and HELMET by
        Siebert and Lin (CC BY 4.0, OSF). Contains information from
        Trafffic_Violations_Triple_Riding_No_Helmet_Plate_ by DevguruCodes, made available under the{" "}
        <a className="underline" href="https://opendatacommons.org/licenses/odbl/1-0/" target="_blank" rel="noreferrer">
          ODbL
        </a>
        .
      </p>
    </>
  );
}
