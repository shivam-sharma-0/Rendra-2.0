package com.code.pal.agent.contract;

/** Accept one optional JSON fence; all content inside still passes the full schema/authority checks. */
final class ModelJson {
    private ModelJson() {}
    static String object(String content) {
        if(content==null || content.length()>48_000)throw new IllegalArgumentException("Model JSON exceeds capacity");
        String text=content.strip();
        if(text.startsWith("```")) {
            int newline=text.indexOf('\n');
            if(newline<0 || !(text.substring(0,newline).strip().equals("```json") || text.substring(0,newline).strip().equals("```")) || !text.endsWith("```"))throw new IllegalArgumentException("Invalid JSON fence");
            text=text.substring(newline+1,text.length()-3).strip();
        }
        if(!text.startsWith("{") || !text.endsWith("}"))throw new IllegalArgumentException("Expected one JSON object without prose");
        return text;
    }
}
