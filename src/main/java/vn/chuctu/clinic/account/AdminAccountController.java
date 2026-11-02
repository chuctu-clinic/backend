package vn.chuctu.clinic.account;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import vn.chuctu.clinic.common.Body;
import vn.chuctu.clinic.common.Errors;
import vn.chuctu.clinic.common.Paging;
import vn.chuctu.clinic.post.Ids;
import vn.chuctu.clinic.security.CurrentUser;

/** Quản lý tài khoản (UC.8.1 đến UC.8.5), phần tài khoản của routes/admin.routes.js. Quyền ADMIN do SecurityConfig. */
@RestController
@RequestMapping("/api/admin/accounts")
public class AdminAccountController {

    private final AccountService accounts;
    private final CurrentUser currentUser;

    public AdminAccountController(AccountService accounts, CurrentUser currentUser) {
        this.accounts = accounts;
        this.currentUser = currentUser;
    }

    @GetMapping
    Map<String, Object> list(@RequestParam(required = false) String q, @RequestParam(required = false) String role,
            @RequestParam(required = false) String status, @RequestParam(required = false) String page,
            @RequestParam(required = false) String limit) {
        Paging paging = Paging.of(page, limit, 20);
        AccountService.AccountPage result = accounts.listAccounts(q, role, status, paging);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", result.items().stream().map(User::toPublic).toList());
        out.put("total", result.total());
        out.put("roleCounts", result.roleCounts());
        out.put("page", paging.page());
        out.put("limit", paging.limit());
        return out;
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) JsonNode json) {
        var data = AccountForms.create(Body.of(json));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("account", accounts.createAccount(data).toPublic()));
    }

    @GetMapping("/{id}")
    Map<String, Object> get(@PathVariable String id) {
        return Map.of("account", accounts.getAccount(accountId(id)).toPublic());
    }

    @PatchMapping("/{id}")
    Map<String, Object> update(@PathVariable String id, @RequestBody(required = false) JsonNode json) {
        // Như Node: kiểm tra dữ liệu trước, tra cứu mã sau
        var data = AccountForms.update(Body.of(json));
        return Map.of("account", accounts.updateAccount(accountId(id), data).toPublic());
    }

    @PostMapping("/{id}/lock")
    Map<String, Object> lock(@PathVariable String id) {
        return Map.of("account", accounts.setAccountActive(currentUser.require(), accountId(id), false).toPublic());
    }

    @PostMapping("/{id}/unlock")
    Map<String, Object> unlock(@PathVariable String id) {
        return Map.of("account", accounts.setAccountActive(currentUser.require(), accountId(id), true).toPublic());
    }

    @PutMapping("/{id}/role")
    Map<String, Object> role(@PathVariable String id, @RequestBody(required = false) JsonNode json) {
        var data = AccountForms.role(Body.of(json));
        return Map.of("account",
                accounts.setRole(currentUser.require(), accountId(id), data.role(), data.tempPassword(), data.email()).toPublic());
    }

    @PostMapping("/{id}/reset-password")
    Map<String, Object> resetPassword(@PathVariable String id, @RequestBody(required = false) JsonNode json) {
        String tempPassword = AccountForms.resetPassword(Body.of(json));
        return Map.of("account", accounts.resetPassword(currentUser.require(), accountId(id), tempPassword).toPublic());
    }

    /** Mã tài khoản là số; mã không phải số hay quá lớn là CastError của Mongoose: 404 chung "Không tìm thấy dữ liệu.". */
    private static long accountId(String id) {
        Long parsed = Ids.parse(id);
        if (parsed == null) throw Errors.notFound();
        return parsed;
    }
}
