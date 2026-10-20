package rw.gov.risa.sso.starter.web;

import java.util.Optional;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import rw.gov.risa.sso.starter.joinkey.JoinKey;
import rw.gov.risa.sso.starter.joinkey.JoinKeyMissingException;
import rw.gov.risa.sso.starter.joinkey.JoinKeyResolver;
import rw.gov.risa.sso.starter.joinkey.JoinKeyValue;

/** Resolves {@code @JoinKey String} / {@code @JoinKey JoinKeyValue} parameters. */
public final class JoinKeyArgumentResolver implements HandlerMethodArgumentResolver {

  private final JoinKeyResolver resolver;

  public JoinKeyArgumentResolver(JoinKeyResolver resolver) {
    this.resolver = resolver;
  }

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    if (!parameter.hasParameterAnnotation(JoinKey.class)) {
      return false;
    }
    Class<?> type = parameter.getParameterType();
    return type == String.class || type == JoinKeyValue.class;
  }

  @Override
  public Object resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    JoinKey annotation = parameter.getParameterAnnotation(JoinKey.class);
    Optional<String> value = resolver.resolve(SecurityContextHolder.getContext().getAuthentication());
    if (value.isEmpty()) {
      if (annotation != null && annotation.required()) {
        throw new JoinKeyMissingException();
      }
      return null;
    }
    if (parameter.getParameterType() == JoinKeyValue.class) {
      return new JoinKeyValue(resolver.column(), value.get());
    }
    return value.get();
  }
}
