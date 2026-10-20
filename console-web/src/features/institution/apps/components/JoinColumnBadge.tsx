import { useTranslation } from "react-i18next";

import { Badge } from "@/components/ui/badge";
import type { JoinColumnSetting } from "@/features/clients/types";

/**
 * An unconfigured client is FLAGGED, not described: its claim would carry
 * email, but nobody chose that — rendering the default as a setting is how a
 * realm reads as configured while nothing is.
 */
export function JoinColumnBadge({ setting, configured }: { setting: JoinColumnSetting; configured?: boolean }) {
  const { t } = useTranslation();
  if (setting.malformed_value) {
    return (
      <Badge variant="destructive" title={t("instApps.joinKey.malformedHint", { value: setting.malformed_value })}>
        {t("instApps.joinKey.invalid")}
      </Badge>
    );
  }
  if (!(configured ?? setting.explicit)) {
    return (
      <Badge variant="destructive" title={t("instApps.joinKey.notSetHint")}>
        {t("instApps.joinKey.notSet")}
      </Badge>
    );
  }
  return <Badge variant="secondary">{setting.join_column}</Badge>;
}
