package com.example.sonicLens.domain.user;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public Map<String, Object> me(Principal principal) {
        User user = userService.findByEmail(principal.getName());
        return toResponse(user);
    }

    @GetMapping("/{id}")
    public Map<String, Object> getById(@PathVariable Long id) {
        User user = userService.findById(id);
        return toResponse(user);
    }

    private Map<String, Object> toResponse(User user) {
        return Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "email", user.getEmail(),
                "createdAt", user.getCreatedAt()
        );
    }
}
