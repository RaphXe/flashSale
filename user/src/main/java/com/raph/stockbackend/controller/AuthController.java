package com.raph.stockbackend.controller;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.raph.stockbackend.entity.User;
import com.raph.stockbackend.repository.UserRepository;
import com.raph.stockbackend.service.AuthService;

@RestController
@RequestMapping("/api/internal/user")
public class AuthController {
    @Autowired
    AuthService authService;

    @Autowired
    UserRepository userRepository;

    @PostMapping("/login")
    public ResponseEntity<?> internalLogin(@RequestBody Map<String, String> credentials) {
        String username = credentials.get("username");
        String password = credentials.get("password");

        Optional<User> userOpt = authService.login(username, password);
        Map<String, String> response = new HashMap<>();

        if(userOpt.isPresent()) {
            if(userOpt.get().getStatus() == 1) {
                response.put("username", userOpt.get().getUsername());
                response.put("message", "账号已被禁用");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
            }
            User user = userOpt.get();
            response.put("username", user.getUsername());
            response.put("id", String.valueOf(user.getId()));

            return ResponseEntity.ok(response);
        } else {
            response.put("message", "用户名或密码错误");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
        }
    }


    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody User user) {
        try {
            user.setStatus(0);
            User savedUser = authService.register(user.getUsername(), user.getPassword());
            Map<String, Object> response = new HashMap<>();
            response.put("id", savedUser.getId());
            response.put("username", savedUser.getUsername());
            response.put("message", "注册成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @GetMapping("/info")
    public ResponseEntity<?> info(@RequestHeader("X-User-Id") String userIdHeader) {
        Map<String, Object> response = new HashMap<>();
        long userId;
        try {
            userId = Long.parseLong(userIdHeader);
        } catch (NumberFormatException e) {
            response.put("message", "无效的用户标识");
            return ResponseEntity.badRequest().body(response);
        }

        User user = userRepository.findById(userId).orElse(null);
        if(user == null) {
            response.put("message", "用户不存在");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
        response.put("username", user.getUsername());
        response.put("email", user.getEmail());
        response.put("phone", user.getPhone());
        return ResponseEntity.ok(response);
    }
 }
