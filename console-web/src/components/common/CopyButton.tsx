import { useState } from "react";
import { Check, Copy } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Button } from "@/components/ui/button";

export function CopyButton({ value, label }: { value: string; label?: string }) {
  const { t } = useTranslation();
  const [done, setDone] = useState(false);
  return (
    <Button
      size="sm"
      variant="outline"
      aria-label={label ?? t("common.copy")}
      onClick={() => {
        void navigator.clipboard?.writeText(value).then(() => {
          setDone(true);
          setTimeout(() => setDone(false), 1500);
        });
      }}
    >
      {done ? <Check /> : <Copy />}
      {label ?? (done ? t("common.copied") : t("common.copy"))}
    </Button>
  );
}
