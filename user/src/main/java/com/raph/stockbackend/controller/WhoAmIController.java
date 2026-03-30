package com.raph.stockbackend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class WhoAmIController {
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
