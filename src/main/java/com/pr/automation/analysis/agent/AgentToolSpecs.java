package com.pr.automation.analysis.agent;

import com.pr.automation.analysis.llm.dto.FunctionDef;
import com.pr.automation.analysis.llm.dto.Tool;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 에이전트가 사용할 도구 스키마/이름/tool_choice 옵션을 캡슐화
@Component
public class AgentToolSpecs {
    public static final String TOOL_READ_FILE = "read_file";
    public static final String TOOL_LIST_DIR = "list_directory";
    public static final String TOOL_SUBMIT = "submit_analysis";

    // LLM에 넘길 도구 목록을 제시
    public List<Tool> tools(boolean onlySubmit) {
        if (onlySubmit) { // onlySubmit 값이 true 라면, LLM 에게 submitTool() 도구만을 제공 ( 지금까지의 정보만으로 결론을 반환하도록 강제 )
            return Collections.singletonList(submitTool());
        }
        return Arrays.asList(readFileTool(), listDirTool(), submitTool()); // LLM에게 3가지의 (파일 읽기, 파일 구조 파악, 결론 도출) 도구 제공
    }

    // 매 분석 라운드마다 tools(도구 목록)와 choice(도구들 중 자유 선택인 auto vs 도구 한 가지 강제인 submit)를 세트로 LLM에 전달합니다.
    public Object choice(boolean forceSubmit) {
        return forceSubmit ? forcedChoice() : "auto";
    }

    // LLM에게 넘길 각 도구의 명세
    private Tool readFileTool() {
        return functionTool(
                TOOL_READ_FILE,
                "코멘트가 가리키는 코드가 호출하는 함수의 정의나 참조하는 설정 파일을 확인할 때 사용, 레포는 PR head 커밋 기준",
                objectSchema(singletonMap("path", stringProp("조회할 파일의 레포 루트 기준 경로 (예 : src/main/Foo.java)")), "path")
        );
    }

    private Tool listDirTool() {
        return functionTool(
                TOOL_LIST_DIR,
                "어떤 파일이 어디 있는지 모를 때 디렉터리 구조 탐색",
                objectSchema(singletonMap("path", stringProp("나열할 디렉터리 경로. 빈 문자열이면 루트.")), "path")
        );
    }

    private Tool submitTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("comment_summary", stringProp("코멘트가 지적/제안하는 핵심 요약 (한국어)"));
        props.put("current_approach", stringProp("현재 구현 방식 (한국어, 해당 없으면 \"해당 없음\")"));
        props.put("suggested_approach", stringProp("코멘트가 제안하는 방식 (한국어, 없으면 \"해당 없음\")"));

        Map<String, Object> verdict = stringProp("판정 + 한 줄 이유 (한국어)");
        verdict.put("enum", Arrays.asList("제안 채택 권장", "현 구현 유지 권장", "추가 논의 필요"));
        props.put("verdict", verdict);

        props.put("reasoning", stringProp("판정 근거. 조회한 코드/설정에 기반한 트레이드오프 포함 2~5문장 (한국어)"));
        props.put("suggested_reply", stringProp("코멘트에 달 정중하고 구체적인 답변 초안 (한국어)"));

        return functionTool(
                TOOL_SUBMIT,
                "코드 조사를 마친 뒤 최종 분석 결과를 제출. 이 도구 호출로 분석 종료.",
                objectSchema(props, "comment_summary", "current_approach", "suggested_approach", "verdict", "reasoning", "suggested_reply")
        );
    }

    private Object forcedChoice() {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", TOOL_SUBMIT);
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

    private static Map<String, Object> singletonMap(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
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
