/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.qlangtech.tis.lang;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.annotation.JSONField;
import com.qlangtech.tis.manage.common.Config;
import com.qlangtech.tis.manage.common.TisUTF8;
import com.qlangtech.tis.order.center.IParamContext;
import com.qlangtech.tis.trigger.util.JsonUtil;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 底层运行时异常运行时可直达web，届时可添加一些格式化处理
 *
 * @author 百岁（baisui@qlangtech.com）
 * @create: 2020-07-23 18:56
 */
public class TisException extends RuntimeException {

    public static ErrMsg getErrMsg(Throwable throwable) {
        TisException except = find(throwable);
        if (except == null) {
            Throwable cause = throwable.getCause();
            return new ErrMsg(org.apache.commons.lang3.exception.ExceptionUtils.getRootCauseMessage(throwable), cause != null ? cause : throwable);
        } else {
            return new ErrMsg(except.getMessage(), except);
        }
    }

    private static TisException find(Throwable throwable) {
        final Throwable[] throwables = ExceptionUtils.getThrowables(throwable);
        for (Throwable ex : throwables) {
            if (TisException.class.isAssignableFrom(ex.getClass())) {
                return (TisException) ex;
            }
        }
        return null;
    }

    public TisException(String message, Throwable cause) {
        super(message, cause);
    }

    public TisException(String message) {
        super(message);
    }


    public static class ErrMsg {

        static final ThreadLocal<SimpleDateFormat> formatOfyyyyMMddHHmmssMMM = new ThreadLocal<SimpleDateFormat>() {
            @Override
            protected SimpleDateFormat initialValue() {
                return new SimpleDateFormat(IParamContext.yyyyMMddHHmmssMMMPattern);
            }
        };

        private final String message;
        @JSONField(serialize = false)
        private final Throwable ex;
        private long logFileName;
        // 异常摘要
        private String abstractInfo;

        public ErrMsg(String message, Throwable ex) {
            this.message = message;
            this.ex = ex;
        }

        public String getLogFileName() {
            return String.valueOf(this.logFileName);
        }

        public String getAbstractInfo() {
            return this.abstractInfo;
        }

        public void setAbstractInfo(String abstractInfo) {
            this.abstractInfo = abstractInfo;
        }

        public long getCreateTime() {
//            return LocalDateTime.parse(String.valueOf(this.logFileName), IParamContext.yyyyMMddHHmmssMMM)
//                    .toEpochSecond(ZoneOffset.UTC);
            try {
                return formatOfyyyyMMddHHmmssMMM.get().parse(String.valueOf(this.logFileName)).getTime();
            } catch (ParseException e) {
                throw new RuntimeException(e);
            }
        }

        public String getMessage() {
            return this.message;
        }

        public ErrMsg writeLogErr() {
            Objects.requireNonNull(ex, "exception can not be null");
            this.logFileName = Long.parseLong(IParamContext.getCurrentMillisecTimeStamp());
            File errLog = getErrLogFile(String.valueOf(this.logFileName));
            StringWriter errWriter = new StringWriter();
            // FileUtils.openOutputStream(errLog)
            try (PrintWriter print = new PrintWriter(errWriter)) {
                ex.printStackTrace(print);
            } catch (Exception e) {
                throw new RuntimeException(errLog.getAbsolutePath(), e);
            }
            JSONObject err = new JSONObject();
            err.put(KEY_ABSTRACT, ex.getMessage());
            err.put(KEY_DETAIL, errWriter.toString());
            try {
                FileUtils.write(errLog, JsonUtil.toString(err), TisUTF8.get());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return this;
        }
    }

    private static final String KEY_DETAIL = "detail";
    private static final String KEY_ABSTRACT = "abstract";

    private static final Pattern p = Pattern.compile("\\d{" + IParamContext.yyyyMMddHHmmssMMMPattern.length() + "}");

    public static List<ErrMsg> getErrorLogs() {
        File errLogDir = getErrLogDir();
        String[] logs = errLogDir.list();

        return Arrays.stream(logs).filter((l) ->
                p.matcher(l).matches()
        ).map((l) -> {
            ErrMsg errMsg = new ErrMsg(null, null);
            errMsg.logFileName = Long.parseLong(l);
            return errMsg;
        }).sorted((a, b) -> (int) (b.logFileName - a.logFileName)).collect(Collectors.toList());
    }

    private static File getErrLogFile(String logFileName) {
        return new File(getErrLogDir(), logFileName);
    }

    private static File getErrLogDir() {
        return new File(Config.getLogDir(), "syserrs");
    }

    public static ILogErrorDetail getLogError(String logFileName) {
        if (StringUtils.isEmpty(logFileName)) {
            throw new IllegalArgumentException("param logFileName can not be null");
        }
        File errLogFile = getErrLogFile(logFileName);

        AtomicReference<JSONObject> error = new AtomicReference<>();

        return new ILogErrorDetail() {
            @Override
            public String getDetail() {
                return getPersisObj().getString(KEY_DETAIL);
            }
            private JSONObject getPersisObj() {
                return error.updateAndGet((pre) -> {
                    try {
                        if (pre == null) {
                            pre = JSON.parseObject(FileUtils.readFileToString(errLogFile, TisUTF8.get()));
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    return pre;
                });
            }
            @Override
            public String getAbstractInfo() {
                return getPersisObj().getString(KEY_ABSTRACT);
            }
        };


    }


    public interface ILogErrorDetail {
        public String getDetail();

        public String getAbstractInfo();
    }

}
