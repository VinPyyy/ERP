package com.wille.erp.system.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginResponse {

    /** Sa-Token 会话令牌；前端存 localStorage，请求头 Authorization 携带 */
    private String token;
}
