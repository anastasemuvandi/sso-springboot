package rw.gov.risa.sso.audit.chain;

import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Inputs whose expected outputs were produced by CPython 3 running the reference formulas
 * verbatim ({@code json.dumps(sort_keys=True, default=str)}, {@code datetime.isoformat()},
 * {@code hashlib.sha256}). If any of these fail, rows written by the Python service would stop
 * verifying under this service.
 */
final class GoldenVectors {

    static final OffsetDateTime T_MICROS = OffsetDateTime.of(2026, 10, 20, 9, 15, 30, 123_456_000, ZoneOffset.UTC);
    static final OffsetDateTime T_WHOLE = OffsetDateTime.of(2026, 10, 20, 9, 15, 30, 0, ZoneOffset.UTC);

    static final String DUMPS = "{\"Z\": 0.1, \"_\": -0.0, \"nested\": {\"a\": 1e-05, \"big\": 12345678901234567890, "
            + "\"k\": 100.0, \"m\": 1e+16, \"z\": [1, 2.5, true, null, \"\\u00e9\\u2713\\ud83d\\ude00\"]}, "
            + "\"outcome\": \"success\", \"quote\": \"a\\\"b\\\\c\\n\\t/\\u007f\", \"session\": null, "
            + "\"username\": \"john.doe@gov.rw\"}";
    static final String V1 = "7854d962d96e9e8a326275751e8bc339b1f6f7b07334bf028f94f2cfa4cc7a99";
    static final String V2 = "ac90dbf5fcc81a3c8a4ef27dc9a0e7c0e561a6757695e7c8146caa71b5bcf489";
    static final String V2_WHOLE_SECOND_EMPTY = "69b33d0b8787e963f8150774d0735ccbee5cd85fc6f06150d178408b6a4138e9";

    private GoldenVectors() {
    }

    static Map<String, Object> details() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("username", "john.doe@gov.rw");
        d.put("outcome", "success");
        d.put("session", null);
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("z", new ArrayList<>(Arrays.asList(1, 2.5, true, null, "\u00e9\u2713\uD83D\uDE00")));
        nested.put("a", 1e-05);
        nested.put("m", 1e16);
        nested.put("k", 100.0);
        nested.put("big", new BigInteger("12345678901234567890"));
        d.put("nested", nested);
        d.put("quote", "a\"b\\c\n\t/\u007f");
        d.put("Z", 0.1);
        d.put("_", -0.0);
        return d;
    }

    static HashChain.HashInput v2Input() {
        return new HashChain.HashInput("auth.login.success", "user-001", T_MICROS, details(), "user-001", "minagri",
                "bob", "minagri-kubaka-web", "10.0.0.1", "INFO");
    }
}
