package com.raph.stockbackend.controller;

import com.raph.stockbackend.entity.User;
import com.raph.stockbackend.service.AuthService;
import com.raph.stockbackend.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import java.net.InetAddress;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    @Autowired
    AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> credentials) {
        String username = credentials.get("username");
        String password = credentials.get("password");

        Optional<User> userOpt = authService.login(username, password);
        Map<String, String> response = new HashMap<>();

        if(userOpt.isPresent()) {
            User user = userOpt.get();
            String token = JwtUtil.generateToken(user.getId());

            response.put("token", token);
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

    @GetMapping("/whoami")
    public ResponseEntity<?> whoami() throws Exception {
        InetAddress localHost = InetAddress.getLocalHost();

        Map<String, String> response = new HashMap<>();
        response.put("ip", localHost.getHostAddress());
        response.put("hostname", localHost.getHostName());
        response.put("timestamp", String.valueOf(System.currentTimeMillis()));
        return ResponseEntity.ok(response);
    }
 }
