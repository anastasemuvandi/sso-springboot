<#ftl output_format="plainText">
<#assign requiredActionsText><#if requiredActions??><#list requiredActions><#items as reqActionItem>${msg("requiredAction.${reqActionItem}")}<#sep>, </#sep></#items></#list></#if></#assign>
${msg("executeActionsBody", link, linkExpiration, realmName, requiredActionsText, linkExpirationFormatter(linkExpiration))}

--
${msg("emailFooter")}
