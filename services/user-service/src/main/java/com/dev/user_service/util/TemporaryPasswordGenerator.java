package com.dev.user_service.util;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class TemporaryPasswordGenerator {

    // Bỏ các ký tự dễ nhầm (I, l, O, 0, 1) vì STAFF sẽ đọc mật khẩu từ email rồi gõ tay
    private static final String LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String ALL = LETTERS + DIGITS;
    private static final int LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    public String generate() {
        char[] chars = new char[LENGTH];
        // Đảm bảo luôn có ít nhất 1 chữ cái và 1 chữ số để đạt policy mật khẩu (mục 1.5 API design)
        chars[0] = LETTERS.charAt(RANDOM.nextInt(LETTERS.length()));
        chars[1] = DIGITS.charAt(RANDOM.nextInt(DIGITS.length()));
        for (int i = 2; i < LENGTH; i++) {
            chars[i] = ALL.charAt(RANDOM.nextInt(ALL.length()));
        }
        // Xáo trộn để 2 ký tự bắt buộc không luôn nằm ở đầu chuỗi
        for (int i = LENGTH - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char tmp = chars[i];
            chars[i] = chars[j];
            chars[j] = tmp;
        }
        return new String(chars);
    }
}