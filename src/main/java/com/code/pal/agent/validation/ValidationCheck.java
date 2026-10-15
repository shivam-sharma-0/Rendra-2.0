package com.code.pal.agent.validation;

import java.util.Objects;

/** A skipped check is evidence of unavailable coverage, never a passing check. */
public record ValidationCheck(String id,Outcome outcome,boolean required,String reason,SkipCause skipCause) {
    public enum Outcome { PASS, FAIL, SKIPPED }
    public enum SkipCause { UNAVAILABLE, PREREQUISITE_FAILED }
    public ValidationCheck(String id,Outcome outcome,boolean required,String reason){this(id,outcome,required,reason,outcome==Outcome.SKIPPED?SkipCause.UNAVAILABLE:null);}
    public ValidationCheck {Objects.requireNonNull(id);Objects.requireNonNull(outcome);reason=reason==null?"":reason;}
    public boolean blocksSuccess(){return required && outcome!=Outcome.PASS;}
    public boolean unavailable(){return required && outcome==Outcome.SKIPPED && skipCause!=SkipCause.PREREQUISITE_FAILED;}
}
