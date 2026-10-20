package rw.gov.risa.sso.wrapper.service.ippis.cli;

import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;

import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisOutcome;
import rw.gov.risa.sso.wrapper.policy.ippisperson.ReconcileOutcome;
import rw.gov.risa.sso.wrapper.service.ippis.DevMirrorSeeder;
import rw.gov.risa.sso.wrapper.service.ippis.IdentityMirrorWriter;
import rw.gov.risa.sso.wrapper.service.ippis.IppisReconcileService;
import rw.gov.risa.sso.wrapper.service.ippis.IppisReconcileService.RunOptions;
import rw.gov.risa.sso.wrapper.service.ippis.IppisScheduler;
import rw.gov.risa.sso.wrapper.service.ippis.RunReport;

/**
 * The IPPIS ops commands (ports of {@code scripts/reconcile_ippis.py}, {@code process_ippis_leavers.py},
 * {@code seed_identity_mirror_dev.py}).:
 *
 * <pre>
 * java -jar sso-wrapper.jar ippis-reconcile [--full] [--dry-run] [--no-mirror] [--require-mirror]
 *      [--fail-on-unmapped] [--max-users=N] [--no-lock]
 * java -jar sso-wrapper.jar ippis-leavers [--dry-run] [--max-users=N] [--no-lock]
 * java -jar sso-wrapper.jar seed-identity-mirror [--dry-run]
 * </pre>
 * (dispatched by the shared {@code cli.OpsCommand} framework: no web server, the process exits
 * with the returned code).
 * Exit codes: 0 ok (also "another run holds the lock"); 1 the run failed; 2 {@code --require-mirror}
 * with no usable mirror / seeding not possible; 3 {@code --require-mirror} and mirror writes failed;
 * 4 {@code --fail-on-unmapped} and unmapped IPPIS codes were seen; 64 usage error. Reconcile runs take
 * the same ShedLock lease as the in-process schedule, so a CLI run never overlaps a scheduled one.
 */
@Component
public class IppisCommands {

    public static final Set<String> COMMANDS = Set.of("ippis-reconcile", "ippis-leavers", "seed-identity-mirror");

    private final IppisReconcileService reconcile;
    private final IdentityMirrorWriter mirror;
    private final DevMirrorSeeder seeder;
    private final LockingTaskExecutor locks;
    private PrintStream out = System.out;

    public IppisCommands(IppisReconcileService reconcile, IdentityMirrorWriter mirror, DevMirrorSeeder seeder,
                         LockProvider lockProvider) {
        this.reconcile = reconcile;
        this.mirror = mirror;
        this.seeder = seeder;
        this.locks = new DefaultLockingTaskExecutor(lockProvider);
    }

    /** Test seam. */
    void printTo(PrintStream out) {
        this.out = out;
    }

    /** Runs one command; never throws (an unexpected failure is exit code 1). */
    public int run(String command, List<String> rawArgs) {
        Args args = new Args(rawArgs);
        if (!args.unknown().isEmpty()) {
            out.println("ERROR: unknown option(s) " + args.unknown() + " for " + command);
            return 64;
        }
        try {
            return execute(command, args);
        } catch (RuntimeException e) {
            out.println("ERROR: " + command + " failed: " + e);
            return 1;
        }
    }

    int execute(String command, Args args) {
        boolean dryRun = args.containsOption("dry-run");
        Integer maxUsers = intOption(args, "max-users");
        return switch (command) {
            case "seed-identity-mirror" -> seeder.seed(dryRun, out);
            case "ippis-leavers" -> locked(args, () -> {
                RunReport r = reconcile.runLeavers(new RunOptions(dryRun, false, maxUsers,
                        rw.gov.risa.sso.wrapper.policy.ippisperson.IppisReconcilePolicy.LEAVER_TRIGGERED_BY));
                print(r);
                return 0;
            });
            case "ippis-reconcile" -> reconcileCommand(args, dryRun, maxUsers);
            default -> 64;
        };
    }

    private int reconcileCommand(Args args, boolean dryRun, Integer maxUsers) {
        boolean noMirror = args.containsOption("no-mirror");
        boolean requireMirror = args.containsOption("require-mirror");
        if (noMirror && requireMirror) {
            out.println("ERROR: --no-mirror conflicts with --require-mirror");
            return 64;
        }
        if (requireMirror) {
            var rec = mirror.recorder();
            if (rec.isEmpty()) {
                out.println("ERROR: --require-mirror was passed, but IDENTITY_DATABASE_URL is not set "
                        + "(docs/runbooks/create-sso-identity-db.md)");
                return 2;
            }
            try {
                rec.get().probe();
            } catch (RuntimeException e) {
                out.println("ERROR: --require-mirror was passed and the mirror database is unreachable: "
                        + e.getClass().getSimpleName());
                return 2;
            }
        }
        boolean full = args.containsOption("full");
        return locked(args, () -> {
            RunOptions opts = new RunOptions(dryRun, !noMirror, maxUsers, IppisReconcileService.TRIGGERED_BY);
            out.println((full ? "Full" : "Delta") + " IPPIS reconcile" + (dryRun ? " (dry run)" : "") + " …");
            RunReport r = full ? reconcile.runFull(opts) : reconcile.runDelta(opts);
            print(r);
            if (requireMirror && r.mirror() != null && ((Number) r.mirror().get("errors")).intValue() > 0) {
                out.println("ERROR: --require-mirror was passed and " + r.mirror().get("errors")
                        + " mirror write(s) failed.");
                return 3;
            }
            if (args.containsOption("fail-on-unmapped") && !r.unmappedCodes().isEmpty()) {
                out.println("ERROR: --fail-on-unmapped was passed and " + r.unmappedCodes().size()
                        + " IPPIS institution code(s) are not in the registry: " + String.join(", ", r.unmappedCodes()));
                return 4;
            }
            return 0;
        });
    }

    private int locked(Args args, Supplier<Integer> job) {
        if (args.containsOption("no-lock")) {
            return job.get();
        }
        try {
            LockingTaskExecutor.TaskResult<Integer> result = locks.executeWithLock(job::get,
                    new LockConfiguration(Instant.now(), IppisScheduler.LOCK, Duration.ofHours(6), Duration.ZERO));
            if (!result.wasExecuted()) {
                out.println("another reconcile holds the lock '" + IppisScheduler.LOCK + "'; skipping this run");
                return 0;
            }
            return result.getResult();
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private void print(RunReport r) {
        Map<String, Object> m = r.toMap();
        StringBuilder line = new StringBuilder("  ");
        for (IppisOutcome s : IppisOutcome.values()) {
            line.append(s.wire()).append('=').append(m.get(s.wire())).append(' ');
        }
        out.println(line + "(total=" + m.get("total") + ")");
        if (r.mirror() != null) {
            out.println("  mirror: recorded=" + m.get("mirrored") + " failed=" + m.get("mirror_errors")
                    + (r.summary().mirrorRefused() > 0 ? " refused=" + r.summary().mirrorRefused() : ""));
        }
        for (ReconcileOutcome o : r.summary().outcomes()) {
            if (Set.of(IppisOutcome.UPDATED, IppisOutcome.DISABLED, IppisOutcome.ERROR, IppisOutcome.NO_USER)
                    .contains(o.status())) {
                out.printf("    %-15s %s  %s%n", o.status().wire(), o.userId(), o.detail() == null ? "" : o.detail());
            }
        }
        if (!r.rehired().isEmpty()) {
            out.println("  re-enabled under the re-hire rule: " + r.rehired());
        }
        if (r.summary().unmappedInstitution() > 0) {
            out.println("  WARNING: " + r.summary().unmappedInstitution() + " record(s) carry an IPPIS institution code "
                    + "that is not in configs/institutions.yaml: " + String.join(", ", r.unmappedCodes()));
            out.println("           Any `institution` attribute they have was left untouched.");
        }
        if (r.summary().ambiguousInstitution() > 0) {
            out.println("  WARNING: " + r.summary().ambiguousInstitution() + " employee(s) hold two primary employments "
                    + "starting the same day, so no institution could be chosen for them.");
        }
        if (r.mirror() != null) {
            Map<String, Object> mm = r.mirror();
            if (((Number) mm.get("reassignments")).intValue() > 0) {
                out.println("  WARNING: employee number(s) now carry a DIFFERENT national id than the one on file: "
                        + mm.get("refused") + " — refused rather than written.");
            }
            if (((Number) mm.get("conflicts")).intValue() > 0) {
                out.println("  WARNING: employee(s) report an email, national id or phone another employee holds: "
                        + mm.get("conflicted") + " — refused rather than written. Fix the duplicate in IPPIS.");
            }
            if (((Number) mm.get("errors")).intValue() > 0) {
                out.println("  WARNING: " + mm.get("errors") + " mirror write(s) failed — the Keycloak work was "
                        + "unaffected, but sso_identity is now behind. First error: " + mm.get("first_error"));
            }
        }
    }

    private static Integer intOption(Args args, String name) {
        String v = args.value(name);
        return v == null || v.isBlank() ? null : Integer.valueOf(v.strip());
    }

    /** {@code --flag} and {@code --name=value} options. */
    record Args(Map<String, String> options, List<String> unknown) {
        static final Set<String> KNOWN = Set.of("full", "dry-run", "no-mirror", "require-mirror", "fail-on-unmapped",
                "max-users", "no-lock");

        Args(List<String> raw) {
            this(new java.util.LinkedHashMap<>(), new java.util.ArrayList<>());
            for (String a : raw == null ? List.<String>of() : raw) {
                if (!a.startsWith("--")) {
                    unknown.add(a);
                    continue;
                }
                String body = a.substring(2);
                int eq = body.indexOf('=');
                String name = eq >= 0 ? body.substring(0, eq) : body;
                if (!KNOWN.contains(name)) {
                    unknown.add(a);
                    continue;
                }
                options.put(name, eq >= 0 ? body.substring(eq + 1) : "");
            }
        }

        boolean containsOption(String name) {
            return options.containsKey(name);
        }

        String value(String name) {
            return options.get(name);
        }
    }
}
