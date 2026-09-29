package com.example.food.memory;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads immutable, explicitly versioned prompts from the application classpath. */
@Component
public class PromptTemplateManager {

    private static final Pattern KEY = Pattern.compile("[a-z0-9-]{1,80}");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z][a-zA-Z0-9]*)\\}\\}");

    public String require(String versionedKey) {
        if (!StringUtils.hasText(versionedKey) || !KEY.matcher(versionedKey.trim()).matches()) {
            throw new IllegalArgumentException("提示词版本标识无效");
        }
        String resource = "/prompts/memory/" + versionedKey.trim() + ".prompt";
        try (InputStream stream = PromptTemplateManager.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalArgumentException("找不到指定的提示词版本：" + versionedKey);
            }
            String template = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!StringUtils.hasText(template)) {
                throw new IllegalStateException("提示词模板不能为空：" + versionedKey);
            }
            return template;
        } catch (IOException exception) {
            throw new IllegalStateException("读取提示词模板失败：" + versionedKey, exception);
        }
    }

    public String render(String versionedKey, Map<String, ?> variables) {
        String template = require(versionedKey);
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (variables == null || !variables.containsKey(name) || variables.get(name) == null) {
                throw new IllegalArgumentException("提示词变量缺失：" + name);
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(String.valueOf(variables.get(name))));
        }
        matcher.appendTail(rendered);
        if (PLACEHOLDER.matcher(rendered).find()) {
            throw new IllegalStateException("提示词仍包含未替换变量：" + versionedKey);
        }
        return rendered.toString();
    }
}
