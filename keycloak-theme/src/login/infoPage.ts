/**
 * True when the info page is the "Your account has been updated." page shown
 * after the set-password link was used (reference info.ftl compared
 * message.summary with msg("accountUpdatedMessage")). Pure.
 */
export function isAccountUpdatedMessage(summary: string | undefined, accountUpdatedText: string): boolean {
    if (summary === undefined) return false;
    return summary.trim() !== "" && summary.trim() === accountUpdatedText.trim();
}
