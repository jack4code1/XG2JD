package com.seckill.controller;

import com.seckill.common.Result;
import com.seckill.dto.ReviewCreateRequest;
import com.seckill.exception.ForbiddenException;
import com.seckill.model.ShopReview;
import com.seckill.repository.MerchantRepository;
import com.seckill.repository.ShopReviewRepository;
import com.seckill.util.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Store reviews: users publish, authenticated visitors query by merchant. */
@RestController
@RequestMapping("/api/merchant/{merchantId}/reviews")
@RequiredArgsConstructor
public class ShopReviewController {

    private final MerchantRepository merchantRepository;
    private final ShopReviewRepository shopReviewRepository;

    @GetMapping
    public Result<List<ShopReview>> list(@PathVariable Long merchantId) {
        requireMerchantExists(merchantId);
        return Result.ok(shopReviewRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId));
    }

    @PostMapping
    public Result<ShopReview> create(@PathVariable Long merchantId,
                                     @Valid @RequestBody ReviewCreateRequest request) {
        if (!"USER".equals(UserContext.getRole())) {
            throw new ForbiddenException("只有普通用户可以发布点评");
        }
        requireMerchantExists(merchantId);

        ShopReview review = new ShopReview();
        review.setMerchantId(merchantId);
        review.setUserId(UserContext.getUserId());
        review.setRating(request.rating());
        review.setContent(request.content().trim());
        return Result.ok(shopReviewRepository.save(review));
    }

    private void requireMerchantExists(Long merchantId) {
        if (!merchantRepository.existsById(merchantId)) {
            throw new IllegalArgumentException("店铺不存在");
        }
    }
}
