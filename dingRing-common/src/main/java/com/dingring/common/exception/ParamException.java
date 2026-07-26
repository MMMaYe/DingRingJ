package com.dingring.common.exception;

/**
 * 参数校验异常，继承 {@link BizException}。
 */
public class ParamException extends BizException {

    public ParamException(String message) {
        super(ErrorCode.PARAM_INVALID, message);
    }
}
