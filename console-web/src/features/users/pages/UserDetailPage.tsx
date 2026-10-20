/**
 * /users/:id — profile, IPPIS link, password, MFA exemption, realm and client
 * roles, app access (grant / revoke / restore), institutions, sessions.
 * Each card reads independently, so one failure costs that card, not the page.
 */
import { useQuery } from "@tanstack/react-query";
import { Link, useParams } from "react-router-dom";
import { ArrowLeft } from "lucide-react";
import { useTranslation } from "react-i18next";

import { PageHeader } from "@/components/common/PageHeader";
import { ErrorState, Loading } from "@/components/common/QueryState";
import { Button } from "@/components/ui/button";
import { userQuery, userRolesQuery } from "../api";
import { UserAppAccess } from "../components/UserAppAccess";
import { ClientRolesCard, GroupsCard, RealmRolesCard, SessionsCard } from "../components/UserAccessCards";
import { IdentityCard, IppisCard } from "../components/UserProfileCards";
import { MfaExemptionCard, PasswordCard } from "../components/UserSecurityCards";
import { fullName } from "../state/userForms";

export function UserDetailPage() {
  const { t } = useTranslation();
  const { id = "" } = useParams();
  const user = useQuery(userQuery(id));
  const roles = useQuery(userRolesQuery(id));

  const back = (
    <Link to="/users" className="inline-flex items-center gap-1 text-muted-foreground hover:text-foreground">
      <ArrowLeft className="h-3.5 w-3.5" /> {t("users.backToUsers")}
    </Link>
  );

  if (user.isPending) return <Loading />;
  if (user.isError) {
    return (
      <div className="max-w-2xl space-y-4">
        <PageHeader title={t("users.notFound")} back={back} />
        <ErrorState error={user.error} onRetry={() => void user.refetch()} />
        <Button asChild variant="outline">
          <Link to="/users">{t("users.backToUsers")}</Link>
        </Button>
      </div>
    );
  }
  const u = user.data;

  return (
    <div className="max-w-4xl space-y-6">
      <PageHeader title={u.username} description={fullName(u) || u.email || undefined} back={back} />
      <IdentityCard user={u} />
      <UserAppAccess userId={u.id} username={u.username} />
      <GroupsCard userId={u.id} />
      {roles.isPending ? (
        <Loading />
      ) : roles.isError ? (
        <ErrorState error={roles.error} title={t("users.roles.loadFailed")} onRetry={() => void roles.refetch()} />
      ) : (
        <>
          <RealmRolesCard userId={u.id} roles={roles.data} />
          <ClientRolesCard userId={u.id} roles={roles.data} />
        </>
      )}
      <MfaExemptionCard user={u} roles={roles.data} />
      <PasswordCard userId={u.id} />
      <IppisCard user={u} />
      <SessionsCard userId={u.id} />
    </div>
  );
}

export const Component = UserDetailPage;
