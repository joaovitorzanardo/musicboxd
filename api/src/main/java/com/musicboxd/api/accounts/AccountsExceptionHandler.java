package com.musicboxd.api.accounts;

import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders this module's errors (our ErrorResponseExceptions, bean-validation failures,
 * unreadable JSON) as RFC 9457 Problem Details instead of an empty error response.
 */
@RestControllerAdvice(basePackageClasses = AccountsExceptionHandler.class)
class AccountsExceptionHandler extends ResponseEntityExceptionHandler {
}
