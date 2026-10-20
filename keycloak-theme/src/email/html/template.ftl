<#--
  Government of Rwanda SSO — branded HTML email shell.
  Table layout + inline styles only (email clients ignore <style>/external CSS).
  No remote images: many government mailboxes block them, and the deployment may be air-gapped.
-->
<#macro emailLayout>
<!DOCTYPE html>
<html<#if locale??> lang="${locale.language}"</#if>>
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>${msg("emailBrandTitle")}</title>
</head>
<body style="margin:0;padding:0;background:#e5e7eb;font-family:Inter,-apple-system,'Segoe UI',Roboto,Arial,sans-serif;color:#0f172a;">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#e5e7eb;padding:24px 12px;">
    <tr>
        <td align="center">
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:560px;background:#ffffff;border-radius:14px;overflow:hidden;">
                <tr>
                    <td style="padding:0;">
                        <table role="presentation" width="100%" cellpadding="0" cellspacing="0">
                            <tr>
                                <td height="6" style="background:#00a1de;font-size:0;line-height:0;">&nbsp;</td>
                                <td height="6" style="background:#fad201;font-size:0;line-height:0;">&nbsp;</td>
                                <td height="6" style="background:#20603d;font-size:0;line-height:0;">&nbsp;</td>
                            </tr>
                        </table>
                    </td>
                </tr>
                <tr>
                    <td style="padding:22px 28px 6px 28px;background:linear-gradient(135deg,#1d72d3,#0a4691);color:#ffffff;">
                        <div style="font-size:16px;font-weight:600;">${msg("emailBrandTitle")}</div>
                        <div style="font-size:12px;opacity:0.85;padding-bottom:16px;">${msg("emailBrandSubtitle")}</div>
                    </td>
                </tr>
                <tr>
                    <td style="padding:24px 28px;font-size:14px;line-height:1.6;">
                        <#nested>
                    </td>
                </tr>
                <tr>
                    <td style="padding:16px 28px 24px 28px;border-top:1px solid #e2e8f0;font-size:12px;line-height:1.5;color:#64748b;">
                        ${msg("emailFooter")}
                    </td>
                </tr>
            </table>
        </td>
    </tr>
</table>
</body>
</html>
</#macro>
