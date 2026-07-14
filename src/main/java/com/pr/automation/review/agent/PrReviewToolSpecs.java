package com.pr.automation.review.agent;

import com.pr.automation.analysis.llm.dto.FunctionDef;
import com.pr.automation.analysis.llm.dto.Tool;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 4단계 리뷰가 사용하는 강제 제출 도구 스키마를 정의
@Component
public class PrReviewToolSpecs {
    public static final String TOOL_SUBMIT_FINDINGS = "submit_findings";
    public static final String TOOL_SUBMIT_REVIEW = "submit_review";

    // 1~3단계: 발견 이슈 목록 + 단계 총평 제출
    public List<Tool> findingsTools() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("findings", findingsArraySchema());
        props.put("summary", stringProp("이 단계 관점에서의 총평 (한국어). 발견이 없으면 그 사실을 명시."));
        Tool tool = functionTool(TOOL_SUBMIT_FINDINGS,
                "이 단계의 리뷰를 마친 뒤 발견 이슈 목록과 총평을 제출한다.",
                objectSchema(props, "findings", "summary"));
        return Collections.singletonList(tool);
    }

    // 최종검증: 1~3단계를 종합한 확정 결과 제출
    public List<Tool> reviewTools() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("overall_summary", stringProp("PR 전체에 대한 종합 요약 (한국어)"));
        props.put("merged_findings", findingsArraySchema());
        props.put("reviewer_focus_notes", stringProp(
                "기계적 이슈를 제외하고, 사람 리뷰어가 집중해서 판단해야 할 본질적 포인트 "
                        + "(로직 정합성, 트레이드오프 수용 여부 등)를 한국어로 정리."));
        Tool tool = functionTool(TOOL_SUBMIT_REVIEW,
                "1~3단계 발견을 종합·중복제거·오탐제거한 최종 리뷰 결과를 제출한다. 이 도구 호출로 리뷰 종료.",
                objectSchema(props, "overall_summary", "merged_findings", "reviewer_focus_notes"));
        return Collections.singletonList(tool);
    }

    public Object findingsChoice() {
        return forcedChoice(TOOL_SUBMIT_FINDINGS);
    }

    public Object reviewChoice() {
        return forcedChoice(TOOL_SUBMIT_REVIEW);
    }

    private static Map<String, Object> findingsArraySchema() {
        Map<String, Object> itemProps = new LinkedHashMap<>();
        itemProps.put("file", stringProp("이슈가 위치한 파일 경로 (불명확하면 \"전반\")"));
        itemProps.put("line", intProp("라인 번호 (모르면 생략)"));
        Map<String, Object> severity = stringProp("심각도");
        severity.put("enum", Arrays.asList("높음", "중간", "낮음"));
        itemProps.put("severity", severity);
        itemProps.put("category", stringProp("분류 (예: null-safety, 예외처리, 동시성, 보안 등)"));
        itemProps.put("title", stringProp("한 줄 요약 (한국어)"));
        itemProps.put("detail", stringProp("구체적 설명: 무엇이/왜 문제인지 (한국어)"));
        itemProps.put("suggestion", stringProp("개선 제안 (한국어)"));

        Map<String, Object> items = new LinkedHashMap<>();
        items.put("type", "object");
        items.put("properties", itemProps);
        items.put("required", Arrays.asList("file", "severity", "category", "title", "detail", "suggestion"));

        Map<String, Object> arr = new LinkedHashMap<>();
        arr.put("type", "array");
        arr.put("description", "발견한 이슈 목록 (없으면 빈 배열)");
        arr.put("items", items);
        return arr;
    }

    private Object forcedChoice(String toolName) {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", toolName);
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("type", "function");
        choice.put("function", fn);
        return choice;
    }

    private static Map<String, Object> stringProp(String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string");
        m.put("description", description);
        return m;
    }

    private static Map<String, Object> intProp(String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "integer");
        m.put("description", description);
        return m;
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, String... required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Arrays.asList(required));
        return schema;
    }

    private static Tool functionTool(String name, String description, Object parameters) {
        return new Tool("function", new FunctionDef(name, description, parameters));
    }
}
