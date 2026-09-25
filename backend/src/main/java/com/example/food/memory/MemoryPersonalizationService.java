package com.example.food.memory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemoryPersonalizationService {

    private final UserMemorySettingsMapper settingsMapper;

    @Autowired
    public MemoryPersonalizationService(UserMemorySettingsMapper settingsMapper) {
        this.settingsMapper = settingsMapper;
    }

    public MemoryPersonalizationState getState(Long userId) {
        requireUser(userId);
        UserMemorySettings settings = settingsMapper.findOwned(userId);
        return settings == null
                ? new MemoryPersonalizationState(true, 0)
                : new MemoryPersonalizationState(
                        !Boolean.FALSE.equals(settings.getPersonalizationEnabled()),
                        safeVersion(settings.getVersion()));
    }

    public boolean isEnabled(Long userId) {
        return getState(userId).enabled();
    }

    @Transactional
    public MemoryPersonalizationState update(Long userId, MemoryPersonalizationRequest request) {
        requireUser(userId);
        if (request == null || request.enabled() == null || request.version() == null || request.version() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "个性化设置无效");
        }

        UserMemorySettings existing = settingsMapper.findOwned(userId);
        if (existing == null) {
            if (request.version() != 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "个性化设置已更新，请刷新后重试");
            }
            UserMemorySettings created = new UserMemorySettings();
            created.setUserId(userId);
            created.setPersonalizationEnabled(request.enabled());
            created.setVersion(1);
            try {
                settingsMapper.insert(created);
                return new MemoryPersonalizationState(request.enabled(), 1);
            } catch (DuplicateKeyException concurrentUpdate) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "个性化设置已更新，请刷新后重试");
            }
        }

        int currentVersion = safeVersion(existing.getVersion());
        boolean currentlyEnabled = !Boolean.FALSE.equals(existing.getPersonalizationEnabled());
        if (request.version() != currentVersion) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "个性化设置已更新，请刷新后重试");
        }
        if (currentlyEnabled == request.enabled()) {
            return new MemoryPersonalizationState(currentlyEnabled, currentVersion);
        }
        if (settingsMapper.updateOwned(userId, currentVersion, request.enabled()) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "个性化设置已被其他请求修改，请刷新后重试");
        }
        return new MemoryPersonalizationState(request.enabled(), currentVersion + 1);
    }

    private void requireUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户身份无效");
        }
    }

    private int safeVersion(Integer version) {
        return version == null ? 0 : Math.max(version, 0);
    }
}
