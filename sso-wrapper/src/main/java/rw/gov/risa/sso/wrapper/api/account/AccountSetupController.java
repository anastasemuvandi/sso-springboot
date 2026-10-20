package rw.gov.risa.sso.wrapper.api.account;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

import io.swagger.v3.oas.annotations.Hidden;
import rw.gov.risa.sso.wrapper.ratelimit.RateLimit;
import rw.gov.risa.sso.wrapper.service.gateway.AccountPageLinks;
import rw.gov.risa.sso.wrapper.service.setuplink.SetupLinkService;
import rw.gov.risa.sso.wrapper.service.setuplink.SetupLinkService.AppLink;
import rw.gov.risa.sso.wrapper.service.setuplink.SetupLinkService.Completion;
import rw.gov.risa.sso.wrapper.service.setuplink.SetupLinkService.LinkCheck;
import rw.gov.risa.sso.wrapper.web.RequestInfo;

/**
 * The public account pages (spec §5.12; port of {@code api/account_setup.py}), server-rendered with
 * Thymeleaf in English and Kinyarwanda. PUBLIC by design — the person cannot sign in yet:
 *
 * <ul>
 *   <li>{@code GET /account/setup?token=} only CHECKS the link (mail scanners open links first);</li>
 *   <li>{@code POST /account/setup} sets the password once through {@link SetupLinkService}, then lists the
 *       bound applications the person can open NOW (access rule re-checked), each linking to its home URL;</li>
 *   <li>{@code GET/POST /account/setup-link}: "didn't get the email?" — the answer is IDENTICAL whatever
 *       happened (the real outcome is audited by the service), throttled per IP here and per account in
 *       the service; {@code return_to} is accepted only as a relative path on the Keycloak public URL.</li>
 * </ul>
 * Every page sends its own CSP (styles from this origin only, forms post only to itself, no script).
 */
@Controller
@Hidden
@RequestMapping("/account")
public class AccountSetupController {

    static final String CSP = "default-src 'none'; style-src 'self'; img-src 'self'; form-action 'self'; "
            + "frame-ancestors 'none'; base-uri 'none'";

    /** Outcomes that re-show the password form (with a message); every other refusal is a dead link. */
    private static final Set<String> FORM_OUTCOMES = Set.of("passwords_differ", "password_empty", "password_rejected");

    private final SetupLinkService setupLinks;
    private final AccountPageLinks links;

    public AccountSetupController(SetupLinkService setupLinks, AccountPageLinks links) {
        this.setupLinks = setupLinks;
        this.links = links;
    }

    // ---- resend ("didn't get the email?") --------------------------------------------------

    @GetMapping("/setup-link")
    @RateLimit(value = "60/minute", key = RateLimit.Key.IP)
    public String setupLinkForm(@RequestParam(value = "username", required = false) String username,
                                @RequestParam(value = "lang", required = false) String lang,
                                @RequestParam(value = "return_to", required = false) String returnTo,
                                Model model, HttpServletResponse response) {
        String code = AccountTexts.lang(lang);
        String accepted = links.acceptedReturnTo(returnTo);
        page(model, response, code, "title", links.backUrl(accepted));
        String other = AccountTexts.EN.equals(code) ? AccountTexts.RW : AccountTexts.EN;
        UriComponentsBuilder sw = UriComponentsBuilder.fromPath("setup-link").queryParam("lang", other);
        if (username != null && !username.isBlank()) {
            sw.queryParam("username", username);
        }
        if (accepted != null) {
            sw.queryParam("return_to", accepted);
        }
        model.addAttribute("switchUrl", sw.encode().build().toUriString());
        model.addAttribute("switchLabel", AccountTexts.RW.equals(other) ? "Kinyarwanda" : "English");
        model.addAttribute("username", username == null ? "" : username);
        model.addAttribute("returnTo", accepted == null ? "" : accepted);
        return "account/setup-link";
    }

    @PostMapping("/setup-link")
    @RateLimit(value = "10/hour", key = RateLimit.Key.IP)
    public String setupLinkRequest(@RequestParam(value = "username", required = false) String username,
                                   @RequestParam(value = "lang", required = false) String lang,
                                   @RequestParam(value = "return_to", required = false) String returnTo,
                                   Model model, HttpServletResponse response) {
        String code = AccountTexts.lang(lang);
        // The real outcome is audited by the service; the page says the same thing whatever it was.
        setupLinks.requestResend(username == null ? "" : username, RequestInfo.clientIp());
        page(model, response, code, "done_title", links.backUrl(links.acceptedReturnTo(returnTo)));
        return "account/setup-link-sent";
    }

    // ---- the emailed link ------------------------------------------------------------------

    @GetMapping("/setup")
    @RateLimit(value = "60/minute", key = RateLimit.Key.IP)
    public String setupForm(@RequestParam(value = "token", required = false) String token,
                            @RequestParam(value = "lang", required = false) String lang,
                            Model model, HttpServletResponse response) {
        String code = AccountTexts.lang(lang);
        if (token == null || token.isBlank()) {
            return invalid(model, response, code);
        }
        LinkCheck check = setupLinks.open(token);
        if (!check.usable() || check.user() == null) {
            return invalid(model, response, code);
        }
        return setForm(model, response, code, token, check.user().getUsername(), null);
    }

    @PostMapping("/setup")
    @RateLimit(value = "20/hour", key = RateLimit.Key.IP)
    public String setupSubmit(@RequestParam(value = "token", required = false) String token,
                              @RequestParam(value = "password", required = false) String password,
                              @RequestParam(value = "confirm", required = false) String confirm,
                              @RequestParam(value = "lang", required = false) String lang,
                              Model model, HttpServletResponse response) {
        String code = AccountTexts.lang(lang);
        if (token == null || token.isBlank()) {
            return invalid(model, response, code);
        }
        Completion result = setupLinks.complete(token, password == null ? "" : password,
                confirm == null ? "" : confirm, RequestInfo.clientIp());
        if (result.completed()) {
            Map<String, String> t = page(model, response, code, "done2_title", null);
            List<AppLink> apps = result.applications() == null ? List.of() : result.applications();
            model.addAttribute("apps", apps);
            model.addAttribute("t", t);
            return "account/done";
        }
        if (!FORM_OUTCOMES.contains(result.outcome())) {
            return invalid(model, response, code);
        }
        Map<String, String> t = AccountTexts.of(code);
        String message = switch (result.outcome()) {
            case "passwords_differ" -> t.get("differ");
            case "password_empty" -> t.get("empty");
            default -> result.message() == null || result.message().isBlank() ? t.get("rules") : result.message();
        };
        String username = result.user() == null ? "" : result.user().getUsername();
        return setForm(model, response, code, token, username, message);
    }

    // ---- rendering -------------------------------------------------------------------------

    private String invalid(Model model, HttpServletResponse response, String code) {
        page(model, response, code, "invalid_title", null);
        model.addAttribute("resendUrl", "setup-link?lang=" + code);
        return "account/invalid";
    }

    private String setForm(Model model, HttpServletResponse response, String code, String token, String username,
                           String error) {
        page(model, response, code, "set_title", null);
        model.addAttribute("token", token);
        model.addAttribute("username", username == null ? "" : username);
        model.addAttribute("error", error);
        return "account/setup";
    }

    private static Map<String, String> page(Model model, HttpServletResponse response, String code, String titleKey,
                                            String back) {
        Map<String, String> t = new LinkedHashMap<>(AccountTexts.of(code));
        model.addAttribute("t", t);
        model.addAttribute("lang", code);
        model.addAttribute("pageTitle", t.get(titleKey));
        model.addAttribute("back", back);
        response.setHeader("Content-Security-Policy", CSP);
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cache-Control", "no-store");
        return t;
    }
}
