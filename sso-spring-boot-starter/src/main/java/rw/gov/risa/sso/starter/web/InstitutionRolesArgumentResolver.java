package rw.gov.risa.sso.starter.web;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import rw.gov.risa.sso.starter.roles.InstitutionRoles;
import rw.gov.risa.sso.starter.roles.InstitutionRolesResolver;

/** Any controller parameter of type {@link InstitutionRoles} gets the caller's view. */
public final class InstitutionRolesArgumentResolver implements HandlerMethodArgumentResolver {

  private final InstitutionRolesResolver resolver;

  public InstitutionRolesArgumentResolver(InstitutionRolesResolver resolver) {
    this.resolver = resolver;
  }

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.getParameterType() == InstitutionRoles.class;
  }

  @Override
  public Object resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    return resolver.current();
  }
}
