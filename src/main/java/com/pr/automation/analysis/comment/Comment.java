package com.pr.automation.analysis.comment;

import com.pr.automation.analysis.comment.dto.CommentAnalysisResult;
import com.pr.automation.analysis.comment.dto.CommentEvent;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Table;
import java.time.Instant;

@Getter
@Table(name = "comment_analysis")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
public class Comment {
    @Id
    @Column(name = "comment_id")
    private Long commentId;

    @Column(nullable = false)
    private String repoFullName;

    @Column(nullable = false)
    private int prNumber;

    private String commentHtmlUrl;

    @Lob
    private String commentSummary;

    @Lob
    private String currentApproach;

    @Lob
    private String suggestedApproach;

    @Lob
    private String verdict;

    @Lob
    private String reasoning;

    @Lob
    private String suggestedReply;

    @Column(nullable = false)
    private Instant analyzedAt;

    private Comment(CommentEvent event, CommentAnalysisResult result, Instant analyzedAt) {
        this.commentId = event.getCommentId();
        this.repoFullName = event.getRepoFullName();
        this.prNumber = event.getPrNumber();
        this.commentHtmlUrl = event.getCommentHtmlUrl();
        this.commentSummary = result.getCommentSummary();
        this.currentApproach = result.getCurrentApproach();
        this.suggestedApproach = result.getSuggestedApproach();
        this.verdict = result.getVerdict();
        this.reasoning = result.getReasoning();
        this.suggestedReply = result.getSuggestedReply();
        this.analyzedAt = analyzedAt;
    }

    public static Comment from(CommentEvent event, CommentAnalysisResult result, Instant analyzedAt) {
        return new Comment(event, result, analyzedAt);
    }
}
