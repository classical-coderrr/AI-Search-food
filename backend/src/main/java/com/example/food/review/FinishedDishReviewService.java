package com.example.food.review;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.food.ai.qwen.QwenVisionClient;
import com.example.food.memory.MemoryBehaviorEpisodeEvent;
import com.example.food.memory.MemoryBehaviorEpisodeRecorder;
import com.example.food.recipe.RecipeRecord;
import com.example.food.recipe.RecipeRecordMapper;
import com.example.food.review.dto.FinishedDishReviewRequest;
import com.example.food.review.dto.FinishedDishReviewResponse;
import com.example.food.review.dto.FinishedDishReviewResult;
import com.example.food.security.AppRole;
import com.example.food.security.AuthPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class FinishedDishReviewService {

    private static final int MAX_HISTORY_LIMIT = 20;
    private static final String FILE_PURPOSE = "finished_dish_review";

    private final FinishedDishReviewMapper reviewMapper;
    private final UploadedFileMapper uploadedFileMapper;
    private final RecipeRecordMapper recipeRecordMapper;
    private final FinishedDishReviewFileStorage fileStorage;
    private final QwenVisionClient qwenVisionClient;
    private final ObjectMapper objectMapper;
    private final MemoryBehaviorEpisodeRecorder memoryEpisodeRecorder;

    @org.springframework.beans.factory.annotation.Autowired
    public FinishedDishReviewService(
            FinishedDishReviewMapper reviewMapper,
            UploadedFileMapper uploadedFileMapper,
            RecipeRecordMapper recipeRecordMapper,
            FinishedDishReviewFileStorage fileStorage,
            QwenVisionClient qwenVisionClient,
            ObjectMapper objectMapper,
            MemoryBehaviorEpisodeRecorder memoryEpisodeRecorder
    ) {
        this.reviewMapper = reviewMapper;
        this.uploadedFileMapper = uploadedFileMapper;
        this.recipeRecordMapper = recipeRecordMapper;
        this.fileStorage = fileStorage;
        this.qwenVisionClient = qwenVisionClient;
        this.objectMapper = objectMapper;
        this.memoryEpisodeRecorder = memoryEpisodeRecorder;
    }

    public FinishedDishReviewService(
            FinishedDishReviewMapper reviewMapper,
            UploadedFileMapper uploadedFileMapper,
            RecipeRecordMapper recipeRecordMapper,
            FinishedDishReviewFileStorage fileStorage,
            QwenVisionClient qwenVisionClient,
            ObjectMapper objectMapper
    ) {
        this(reviewMapper, uploadedFileMapper, recipeRecordMapper, fileStorage, qwenVisionClient,
                objectMapper, null);
    }

    @Transactional
    public FinishedDishReviewResponse create(
            FinishedDishReviewRequest request,
            MultipartFile image,
            AuthPrincipal principal
    ) {
        FinishedDishReviewFileStorage.UploadedImage uploadedImage = fileStorage.readAndValidate(image);
        boolean regularUser = isRegularUser(principal);
        Long userId = regularUser ? principal.id() : null;
        Long recipeId = regularUser ? resolveOwnedRecipeId(request.recipeId(), userId) : null;
        FinishedDishReviewResult result = toResult(
                request,
                qwenVisionClient.reviewFinishedDish(
                        uploadedImage.contentType(),
                        uploadedImage.bytes(),
                        new QwenVisionClient.FinishedDishReviewContext(
                                request.recipeTitle().trim(),
                                normalizeItems(request.ingredients()),
                                normalizeItems(request.steps())
                        )
                )
        );

        if (!regularUser) {
            return new FinishedDishReviewResponse(null, null, result, false, null);
        }

        FinishedDishReviewFileStorage.StoredFile storedFile = fileStorage.store(uploadedImage);
        try {
            UploadedFile fileRecord = new UploadedFile();
            fileRecord.setUserId(userId);
            fileRecord.setOriginalName(storedFile.originalName());
            fileRecord.setStoredName(storedFile.storedName());
            fileRecord.setContentType(storedFile.contentType());
            fileRecord.setFileSize(storedFile.fileSize());
            fileRecord.setStoragePath(storedFile.storagePath());
            fileRecord.setPurpose(FILE_PURPOSE);
            fileRecord.setCreatedAt(LocalDateTime.now());
            uploadedFileMapper.insert(fileRecord);

            FinishedDishReviewRecord record = new FinishedDishReviewRecord();
            record.setUserId(userId);
            record.setRecipeId(recipeId);
            record.setUploadedFileId(fileRecord.getId());
            record.setReviewResult(toJson(result));
            record.setAiModel(result.provider() + ":" + result.model());
            record.setCreatedAt(LocalDateTime.now());
            reviewMapper.insert(record);
            recordFinishedDishReview(userId, record, request, result);

            return new FinishedDishReviewResponse(record.getId(), recipeId, result, true, record.getCreatedAt());
        } catch (RuntimeException exception) {
            fileStorage.deleteQuietly(storedFile.storedName());
            throw exception;
        }
    }

    private void recordFinishedDishReview(
            Long userId,
            FinishedDishReviewRecord record,
            FinishedDishReviewRequest request,
            FinishedDishReviewResult result
    ) {
        if (memoryEpisodeRecorder == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reviewId", record.getId());
        payload.put("recipeId", record.getRecipeId());
        payload.put("recipeTitle", result.recipeTitle());
        payload.put("overallScore", result.overallScore());
        payload.put("summary", result.summary());
        payload.put("ingredients", request.ingredients());
        payload.put("provider", result.provider());
        payload.put("model", result.model());
        memoryEpisodeRecorder.record(new MemoryBehaviorEpisodeEvent(
                userId,
                null,
                null,
                "FINISHED_DISH_REVIEW",
                "FINISHED_DISH_REVIEW",
                String.valueOf(record.getId()),
                "finished-review:" + record.getId(),
                "finished-review:" + record.getId(),
                "成品菜评价：" + result.recipeTitle(),
                payload,
                record.getCreatedAt(),
                new BigDecimal("0.8000")
        ));
    }

    public List<FinishedDishReviewResponse> list(Long userId, Long recipeId, int limit) {
        validateHistoryLimit(limit);
        QueryWrapper<FinishedDishReviewRecord> query = new QueryWrapper<FinishedDishReviewRecord>()
                .eq("user_id", userId)
                .orderByDesc("created_at")
                .last("LIMIT " + limit);
        if (recipeId != null) {
            query.eq("recipe_id", recipeId);
        }
        return reviewMapper.selectList(query).stream()
                .map(record -> new FinishedDishReviewResponse(
                        record.getId(),
                        record.getRecipeId(),
                        fromJson(record.getReviewResult()),
                        true,
                        record.getCreatedAt()
                ))
                .toList();
    }

    @Transactional
    public void delete(Long userId, Long reviewId) {
        FinishedDishReviewRecord review = reviewMapper.selectById(reviewId);
        if (review == null || !userId.equals(review.getUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "成品评价不存在");
        }

        UploadedFile file = findOwnedReviewFile(userId, review.getUploadedFileId());
        reviewMapper.deleteById(review.getId());
        if (file == null) {
            return;
        }

        uploadedFileMapper.deleteById(file.getId());
        fileStorage.deleteQuietly(file.getStoredName());
    }

    public ReviewImage loadOwnedImage(Long userId, Long reviewId) {
        FinishedDishReviewRecord review = reviewMapper.selectById(reviewId);
        if (review == null || !userId.equals(review.getUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "成品评价不存在");
        }
        UploadedFile file = uploadedFileMapper.selectById(review.getUploadedFileId());
        if (file == null || !userId.equals(file.getUserId()) || !FILE_PURPOSE.equals(file.getPurpose())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "成品图文件不存在");
        }
        return new ReviewImage(fileStorage.load(file.getStoredName()), file.getContentType(), file.getOriginalName());
    }

    private UploadedFile findOwnedReviewFile(Long userId, Long uploadedFileId) {
        if (uploadedFileId == null) {
            return null;
        }
        UploadedFile file = uploadedFileMapper.selectById(uploadedFileId);
        if (file == null || !userId.equals(file.getUserId()) || !FILE_PURPOSE.equals(file.getPurpose())) {
            return null;
        }
        return file;
    }

    private FinishedDishReviewResult toResult(
            FinishedDishReviewRequest request,
            QwenVisionClient.FinishedDishReview review
    ) {
        return new FinishedDishReviewResult(
                request.recipeTitle().trim(),
                review.overallScore(),
                new FinishedDishReviewResult.Dimension(review.color().score(), review.color().comment()),
                new FinishedDishReviewResult.Dimension(review.doneness().score(), review.doneness().comment()),
                new FinishedDishReviewResult.Dimension(review.plating().score(), review.plating().comment()),
                review.summary(),
                review.strengths(),
                review.issues().stream()
                        .map(issue -> new FinishedDishReviewResult.Issue(
                                issue.severity(), issue.title(), issue.evidence(), issue.suggestion()
                        ))
                        .toList(),
                review.safetyNote(),
                review.provider(),
                review.model()
        );
    }

    private Long resolveOwnedRecipeId(Long recipeId, Long userId) {
        if (recipeId == null) {
            return null;
        }
        RecipeRecord recipe = recipeRecordMapper.selectById(recipeId);
        if (recipe == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "关联菜谱不存在");
        }
        if (!userId.equals(recipe.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权关联该菜谱");
        }
        return recipeId;
    }

    private List<String> normalizeItems(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .limit(30)
                .toList();
    }

    private boolean isRegularUser(AuthPrincipal principal) {
        return principal != null && principal.role() == AppRole.USER;
    }

    private String toJson(FinishedDishReviewResult value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("成品图评价结果保存失败", exception);
        }
    }

    private FinishedDishReviewResult fromJson(String value) {
        try {
            return objectMapper.readValue(value, FinishedDishReviewResult.class);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "成品图评价历史数据损坏", exception);
        }
    }

    private void validateHistoryLimit(int limit) {
        if (limit < 1 || limit > MAX_HISTORY_LIMIT) {
            throw new IllegalArgumentException("评价历史数量必须在 1 到 " + MAX_HISTORY_LIMIT + " 之间");
        }
    }

    public record ReviewImage(byte[] bytes, String contentType, String originalName) {
    }
}
