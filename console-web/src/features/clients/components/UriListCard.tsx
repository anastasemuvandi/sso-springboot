/** A textarea of URIs (one per line) saved with one PUT. */
import { useState, type ReactNode } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";

import { notify } from "@/components/common/toast";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { clientKeys, splitUris } from "../api";

export function UriListCard({
  id,
  clientId,
  title,
  description,
  label,
  help,
  initial,
  save,
  saveLabel,
  footerStart,
}: {
  id: string;
  clientId: string;
  title: string;
  description: ReactNode;
  label: string;
  help: ReactNode;
  initial: string[];
  save: (uris: string[]) => Promise<unknown>;
  saveLabel: string;
  footerStart?: ReactNode;
}) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [text, setText] = useState(initial.join("\n"));
  const m = useMutation({
    mutationFn: () => save(splitUris(text)),
    onSuccess: () => {
      notify.success(t("common.saved"));
      void qc.invalidateQueries({ queryKey: clientKeys.detail(clientId) });
    },
    onError: (e) => notify.error(e),
  });
  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-2">
        <Label htmlFor={id}>{label}</Label>
        <Textarea id={id} rows={5} value={text} onChange={(e) => setText(e.target.value)} className="font-mono text-xs" />
        <p className="text-xs text-muted-foreground">{help}</p>
      </CardContent>
      <CardFooter className="flex justify-between">
        <div>{footerStart}</div>
        <Button onClick={() => m.mutate()} disabled={m.isPending}>
          {m.isPending ? t("common.saving") : saveLabel}
        </Button>
      </CardFooter>
    </Card>
  );
}
