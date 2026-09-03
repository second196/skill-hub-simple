package com.km.skillhub.review.controller;

import com.km.skillhub.review.model.ReviewCommand;
import com.km.skillhub.review.model.entity.SkillReviewTaskEntity;
import com.km.skillhub.review.service.ReviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/reviews")
public class ReviewController {
    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) { this.reviewService = reviewService; }

    @GetMapping
    public ResponseEntity<List<SkillReviewTaskEntity>> list(@RequestParam(required = false) String status,
                                                             Authentication authentication) {
        return ResponseEntity.ok(reviewService.list(status, authentication.getName()));
    }

    @PostMapping
    public ResponseEntity<SkillReviewTaskEntity> submit(@RequestBody ReviewCommand command,
                                                        Authentication authentication) {
        return ResponseEntity.ok(reviewService.submit(command, authentication.getName()));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<SkillReviewTaskEntity> approve(@PathVariable Long id,
                                                         @RequestBody(required = false) ReviewCommand command,
                                                         Authentication authentication) {
        return ResponseEntity.ok(reviewService.finish(id, "APPROVED", comment(command), authentication.getName()));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<SkillReviewTaskEntity> reject(@PathVariable Long id,
                                                        @RequestBody(required = false) ReviewCommand command,
                                                        Authentication authentication) {
        return ResponseEntity.ok(reviewService.finish(id, "REJECTED", comment(command), authentication.getName()));
    }

    @PostMapping("/{id}/withdraw")
    public ResponseEntity<SkillReviewTaskEntity> withdraw(@PathVariable Long id,
                                                          @RequestBody(required = false) ReviewCommand command,
                                                          Authentication authentication) {
        return ResponseEntity.ok(reviewService.finish(id, "WITHDRAWN", comment(command), authentication.getName()));
    }

    private String comment(ReviewCommand command) { return command == null ? null : command.getComment(); }
}
