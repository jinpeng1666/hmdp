package com.hmdp.dto;

import com.hmdp.utils.RegexPatterns;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

@Data
public class LoginFormDTO {
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = RegexPatterns.PHONE_REGEX, message = "手机号格式错误")
    private String phone;

    @Pattern(regexp = RegexPatterns.VERIFY_CODE_REGEX, message = "验证码格式错误")
    private String code;

    @Pattern(regexp = RegexPatterns.PASSWORD_REGEX, message = "密码格式错误")
    private String password;
}
