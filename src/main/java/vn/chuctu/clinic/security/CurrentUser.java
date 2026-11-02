package vn.chuctu.clinic.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import vn.chuctu.clinic.account.User;
import vn.chuctu.clinic.common.Errors;

/** Tài khoản của yêu cầu hiện tại (req.user của bản Node). */
@Component
public class CurrentUser {

    /** Tài khoản đang đăng nhập, null nếu chưa đăng nhập. */
    public User get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof User user ? user : null;
    }

    /** Như requireAuth: chưa đăng nhập thì 401. */
    public User require() {
        User user = get();
        if (user == null) throw Errors.unauthorized();
        return user;
    }
}
