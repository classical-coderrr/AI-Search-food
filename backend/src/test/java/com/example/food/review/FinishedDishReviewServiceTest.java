package com.example.food.review;

import com.example.food.ai.qwen.QwenVisionClient;
import com.example.food.memory.MemoryBehaviorEpisodeEvent;
import com.example.food.memory.MemoryBehaviorEpisodeRecorder;
import com.example.food.recipe.RecipeRecord;
import com.example.food.recipe.RecipeRecordMapper;
import com.example.food.review.dto.FinishedDishReviewRequest;
import com.example.food.review.dto.FinishedDishReviewResponse;
import com.example.food.security.AppRole;
import com.example.food.security.AuthPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinishedDishReviewServiceTest {

    @Mock
    private FinishedDishReviewMapper reviewMapper;

    @Mock
    private UploadedFileMapper uploadedFileMapper;

    @Mock
    private RecipeRecordMapper recipeRecordMapper;

    @Mock
    private FinishedDishReviewFileStorage fileStorage;

    @Mock
    private QwenVisionClient qwenVisionClient;

    @Mock
    private MemoryBehaviorEpisodeRecorder memoryEpisodeRecorder;

    private FinishedDishReviewService service;

    @BeforeEach
    void setUp() {
        service = new FinishedDishReviewService(
                reviewMapper,
                uploadedFileMapper,
                recipeRecordMapper,
                fileStorage,
                qwenVisionClient,
                new ObjectMapper(),
                memoryEpisodeRecorder
        );
    }

    @Test
    void anonymousUserReceivesPreviewWithoutPersistingImageOrHistory() {
        MockMultipartFile image = imageFile();
        when(fileStorage.readAndValidate(image)).thenReturn(uploadedImage());
        when(qwenVisionClient.reviewFinishedDish(any(), any(), any())).thenReturn(aiReview());

        FinishedDishReviewResponse response = service.create(request(null), image, null);

        assertThat(response.saved()).isFalse();
        assertThat(response.id()).isNull();
        assertThat(response.result().overallScore()).isEqualTo(86);
        verify(fileStorage, never()).store(any());
        verify(uploadedFileMapper, never()).insert((UploadedFile) any());
        verify(reviewMapper, never()).insert((FinishedDishReviewRecord) any());
    }

    @Test
    void userReviewIsSavedAndBoundToOwnedRecipe() {
        MockMultipartFile image = imageFile();
        RecipeRecord recipe = new RecipeRecord();
        recipe.setId(21L);
        recipe.setUserId(7L);
        when(fileStorage.readAndValidate(image)).thenReturn(uploadedImage());
        when(qwenVisionClient.reviewFinishedDish(any(), any(), any())).thenReturn(aiReview());
        when(recipeRecordMapper.selectById(21L)).thenReturn(recipe);
        when(fileStorage.store(any())).thenReturn(new FinishedDishReviewFileStorage.StoredFile(
                "dish.jpg", "saved.jpg", "image/jpeg", 3L, "D:/test/saved.jpg"
        ));
        doAnswer(invocation -> {
            UploadedFile file = invocation.getArgument(0);
            file.setId(41L);
            return 1;
        }).when(uploadedFileMapper).insert((UploadedFile) any());
        doAnswer(invocation -> {
            FinishedDishReviewRecord review = invocation.getArgument(0);
            review.setId(51L);
            return 1;
        }).when(reviewMapper).insert((FinishedDishReviewRecord) any());

        FinishedDishReviewResponse response = service.create(
                request(21L),
                image,
                new AuthPrincipal(7L, "13800138000", AppRole.USER)
        );

        assertThat(response.saved()).isTrue();
        assertThat(response.id()).isEqualTo(51L);
        assertThat(response.recipeId()).isEqualTo(21L);
        ArgumentCaptor<FinishedDishReviewRecord> reviewCaptor = ArgumentCaptor.forClass(FinishedDishReviewRecord.class);
        verify(reviewMapper).insert(reviewCaptor.capture());
        assertThat(reviewCaptor.getValue().getUploadedFileId()).isEqualTo(41L);
        assertThat(reviewCaptor.getValue().getReviewResult()).contains("番茄炒蛋");
        verify(memoryEpisodeRecorder).record(any(MemoryBehaviorEpisodeEvent.class));
    }

    @Test
    void userCannotSpendVisionCallOnRecipeOwnedBySomeoneElse() {
        MockMultipartFile image = imageFile();
        RecipeRecord recipe = new RecipeRecord();
        recipe.setId(21L);
        recipe.setUserId(8L);
        when(fileStorage.readAndValidate(image)).thenReturn(uploadedImage());
        when(recipeRecordMapper.selectById(21L)).thenReturn(recipe);

        assertThatThrownBy(() -> service.create(
                request(21L),
                image,
                new AuthPrincipal(7L, "13800138000", AppRole.USER)
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        verify(qwenVisionClient, never()).reviewFinishedDish(any(), any(), any());
    }

    @Test
    void historyOnlyContainsTheCurrentUsersRecords() throws Exception {
        FinishedDishReviewRecord record = new FinishedDishReviewRecord();
        record.setId(61L);
        record.setUserId(7L);
        record.setRecipeId(21L);
        record.setCreatedAt(LocalDateTime.now());
        record.setReviewResult(new ObjectMapper().writeValueAsString(result()));
        when(reviewMapper.selectList(any())).thenReturn(List.of(record));

        List<FinishedDishReviewResponse> history = service.list(7L, 21L, 10);

        assertThat(history).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(61L);
            assertThat(item.result().recipeTitle()).isEqualTo("番茄炒蛋");
        });
    }

    @Test
    void deletesOwnedReviewAndItsFinishedDishFileAfterDatabaseRecords() {
        FinishedDishReviewRecord review = review(51L, 7L, 41L);
        UploadedFile file = uploadedFile(41L, 7L, "saved.jpg", "finished_dish_review");
        when(reviewMapper.selectById(51L)).thenReturn(review);
        when(uploadedFileMapper.selectById(41L)).thenReturn(file);

        service.delete(7L, 51L);

        var order = inOrder(reviewMapper, uploadedFileMapper, fileStorage);
        order.verify(reviewMapper).deleteById((java.io.Serializable) 51L);
        order.verify(uploadedFileMapper).deleteById((java.io.Serializable) 41L);
        order.verify(fileStorage).deleteQuietly("saved.jpg");
    }

    @Test
    void deletesReviewWithoutAnAssociatedFile() {
        when(reviewMapper.selectById(51L)).thenReturn(review(51L, 7L, null));

        service.delete(7L, 51L);

        verify(reviewMapper).deleteById((java.io.Serializable) 51L);
        verify(uploadedFileMapper, never()).selectById(any());
        verify(uploadedFileMapper, never()).deleteById((java.io.Serializable) 41L);
        verifyNoInteractions(fileStorage);
    }

    @Test
    void preservesFileRecordAndDiskFileWhenAssociatedFileIsNotAReviewFile() {
        when(reviewMapper.selectById(51L)).thenReturn(review(51L, 7L, 41L));
        when(uploadedFileMapper.selectById(41L))
                .thenReturn(uploadedFile(41L, 7L, "ingredient.jpg", "ingredient_recognition"));

        service.delete(7L, 51L);

        verify(reviewMapper).deleteById((java.io.Serializable) 51L);
        verify(uploadedFileMapper, never()).deleteById((java.io.Serializable) 41L);
        verifyNoInteractions(fileStorage);
    }

    @Test
    void treatsMissingAndAnotherUsersReviewAsNotFound() {
        when(reviewMapper.selectById(51L)).thenReturn(null, review(51L, 8L, 41L));

        assertThatThrownBy(() -> service.delete(7L, 51L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException error = (ResponseStatusException) exception;
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(error.getReason()).isEqualTo("成品评价不存在");
                });
        assertThatThrownBy(() -> service.delete(7L, 51L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException error = (ResponseStatusException) exception;
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(error.getReason()).isEqualTo("成品评价不存在");
                });
        verify(reviewMapper, never()).deleteById((java.io.Serializable) 51L);
    }

    private FinishedDishReviewRecord review(Long id, Long userId, Long uploadedFileId) {
        FinishedDishReviewRecord review = new FinishedDishReviewRecord();
        review.setId(id);
        review.setUserId(userId);
        review.setUploadedFileId(uploadedFileId);
        return review;
    }

    private UploadedFile uploadedFile(Long id, Long userId, String storedName, String purpose) {
        UploadedFile file = new UploadedFile();
        file.setId(id);
        file.setUserId(userId);
        file.setStoredName(storedName);
        file.setPurpose(purpose);
        return file;
    }

    private FinishedDishReviewRequest request(Long recipeId) {
        return new FinishedDishReviewRequest(
                recipeId,
                "番茄炒蛋",
                List.of("番茄 2 个", "鸡蛋 3 个"),
                List.of("炒熟鸡蛋", "加入番茄翻炒")
        );
    }

    private MockMultipartFile imageFile() {
        return new MockMultipartFile("image", "dish.jpg", "image/jpeg", new byte[]{1, 2, 3});
    }

    private FinishedDishReviewFileStorage.UploadedImage uploadedImage() {
        return new FinishedDishReviewFileStorage.UploadedImage(new byte[]{1, 2, 3}, "image/jpeg", "dish.jpg");
    }

    private QwenVisionClient.FinishedDishReview aiReview() {
        return new QwenVisionClient.FinishedDishReview(
                86,
                new QwenVisionClient.VisualDimension(88, "番茄颜色明亮"),
                new QwenVisionClient.VisualDimension(82, "鸡蛋火候合适"),
                new QwenVisionClient.VisualDimension(80, "盘边可以更整洁"),
                "成品色泽自然，整体完成度较好",
                List.of("食材层次清晰"),
                List.of(new QwenVisionClient.FinishedDishIssue(
                        "low", "汤汁略多", "盘底可见少量水分", "缩短番茄翻炒时间"
                )),
                "仅根据照片提供视觉烹饪建议，无法替代食品安全判断。",
                "qwen",
                "qwen-vl-plus"
        );
    }

    private com.example.food.review.dto.FinishedDishReviewResult result() {
        return new com.example.food.review.dto.FinishedDishReviewResult(
                "番茄炒蛋",
                86,
                new com.example.food.review.dto.FinishedDishReviewResult.Dimension(88, "番茄颜色明亮"),
                new com.example.food.review.dto.FinishedDishReviewResult.Dimension(82, "鸡蛋火候合适"),
                new com.example.food.review.dto.FinishedDishReviewResult.Dimension(80, "盘边可以更整洁"),
                "成品色泽自然，整体完成度较好",
                List.of("食材层次清晰"),
                List.of(),
                "仅根据照片提供视觉烹饪建议，无法替代食品安全判断。",
                "qwen",
                "qwen-vl-plus"
        );
    }
}
