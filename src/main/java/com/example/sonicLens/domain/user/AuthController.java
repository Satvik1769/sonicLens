package com.example.sonicLens.domain.user;

import com.example.sonicLens.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final AuthenticationManager authManager;
    private final JwtUtil jwtUtil;

    public record RegisterRequest(String username, String email, String password) {}
    public record LoginRequest(String email, String password) {}

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@RequestBody RegisterRequest req) {
        User user = userService.register(req.username(), req.email(), req.password());
        return Map.of("id", user.getId(), "username", user.getUsername(), "email", user.getEmail());
    }

    @PostMapping("/login")
    public Map<String, String> login(@RequestBody LoginRequest req) {
        authManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.email(), req.password())
        );
        String token = jwtUtil.generateToken(req.email());
        return Map.of("token", token);
    }
}
