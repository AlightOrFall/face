/*
 * Copyright 2017 Baidu, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.baidu.aip.client;

import java.io.UnsupportedEncodingException;
import java.net.Proxy;
import java.util.Calendar;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.json.JSONException;
import org.json.JSONObject;

import com.baidu.aip.auth.CloudAuth;
import com.baidu.aip.auth.DevAuth;
import com.baidu.aip.error.AipError;
import com.baidu.aip.http.AipHttpClient;
import com.baidu.aip.http.AipRequest;
import com.baidu.aip.http.AipResponse;
import com.baidu.aip.http.Headers;
import com.baidu.aip.http.HttpContentType;
import com.baidu.aip.http.HttpMethodName;
import com.baidu.aip.util.AipClientConfiguration;
import com.baidu.aip.util.AipClientConst;
import com.baidu.aip.util.SignUtil;
import com.baidu.aip.util.Util;

public abstract class BaseClient {

    protected String appId;
    protected String aipKey;
    protected String aipToken;
    protected String accessToken;   // 涓嶉�傜敤浜庝娇鐢ㄥ叕鏈変簯ak/sk鐨勭敤鎴�
    protected AtomicBoolean isAuthorized;
    protected AtomicBoolean isBceKey;   // 鏄惁涓哄叕鏈変簯鐢ㄦ埛
    protected Calendar expireDate;
    protected AuthState state;
    protected AipClientConfiguration config;
    protected static final Logger LOGGER = Logger.getLogger(BaseClient.class);

    class AuthState {

        private EAuthState state;

        public AuthState() {
            state = EAuthState.STATE_UNKNOWN;
        }

        public String toString() {
            return state.name();
        }

        public EAuthState getState() {
            return state;
        }

        public void setState(EAuthState state) {
            this.state = state;
        }

        public void transfer(boolean value) {
            switch (state) {
                case STATE_UNKNOWN: {
                    if (value) {
                        state = EAuthState.STATE_AIP_AUTH_OK;
                        isBceKey.set(false);
                    }
                    else {
                        state = EAuthState.STATE_TRUE_CLOUD_USER;
                        isBceKey.set(true);
                    }
                    break;
                }
                case STATE_AIP_AUTH_OK: {
                    if (value) {
                        state = EAuthState.STATE_TRUE_AIP_USER;
                        isBceKey.set(false);
                        isAuthorized.set(true);
                    }
                    else {
                        state = EAuthState.STATE_POSSIBLE_CLOUD_USER;
                        isBceKey.set(true);
                    }
                    break;
                }
                case STATE_TRUE_AIP_USER:
                    break;
                case STATE_POSSIBLE_CLOUD_USER: {
                    if (value) {
                        state = EAuthState.STATE_TRUE_CLOUD_USER;
                        isBceKey.set(true);
                    }
                    else {
                        state = EAuthState.STATE_TRUE_AIP_USER;
                        isBceKey.set(false);
                        isAuthorized.set(true);
                    }
                    break;
                }
                case STATE_TRUE_CLOUD_USER:
                    break;
                default:
                    break;
            }
        }
    }

    /*
     * BaseClient constructor, default as AIP user
     */
    protected BaseClient(String appId, String apiKey, String secretKey) {
        this.appId = appId;
        this.aipKey = apiKey;
        this.aipToken = secretKey;
        isAuthorized = new AtomicBoolean(false);
        isBceKey = new AtomicBoolean(false);
        accessToken = null;
        expireDate = null;
        state = new AuthState();

        // init logging
        String log4jConf = System.getProperty(AipClientConst.LOG4J_CONF_PROPERTY);
        if (log4jConf != null && !log4jConf.equals("")) {
            PropertyConfigurator.configure(log4jConf);
        }
        else {
            BasicConfigurator.configure();
        }
    }

    /**
     *
     * @param timeout 鏈嶅姟鍣ㄥ缓绔嬭繛鎺ョ殑瓒呮椂鏃堕棿锛堝崟浣嶏細姣锛�
     */
    public void setConnectionTimeoutInMillis(int timeout) {
        if (config == null) {
            config = new AipClientConfiguration();
        }
        this.config.setConnectionTimeoutMillis(timeout);
    }

    /**
     *
     * @param timeout 閫氳繃鎵撳紑鐨勮繛鎺ヤ紶杈撴暟鎹殑瓒呮椂鏃堕棿锛堝崟浣嶏細姣锛�
     */
    public void setSocketTimeoutInMillis(int timeout) {
        if (config == null) {
            config = new AipClientConfiguration();
        }
        this.config.setSocketTimeoutMillis(timeout);
    }

    /**
     * 璁剧疆璁块棶缃戠粶闇�瑕佺殑http浠ｇ悊
     * @param host 浠ｇ悊鏈嶅姟鍣ㄥ湴鍧�
     * @param port 浠ｇ悊鏈嶅姟鍣ㄧ鍙�
     */
    public void setHttpProxy(String host, int port) {
        if (config == null) {
            config = new AipClientConfiguration();
        }
        this.config.setProxy(host, port, Proxy.Type.HTTP);
    }

    /**
     * 璁剧疆璁块棶缃戠粶闇�瑕佺殑socket浠ｇ悊
     * @param host 浠ｇ悊鏈嶅姟鍣ㄥ湴鍧�
     * @param port 浠ｇ悊鏈嶅姟鍣ㄧ鍙�
     */
    public void setSocketProxy(String host, int port) {
        if (config == null) {
            config = new AipClientConfiguration();
        }
        this.config.setProxy(host, port, Proxy.Type.SOCKS);
    }

    /**
     * get OAuth access token, synchronized function
     * @param config 缃戠粶杩炴帴璁剧疆
     */
    protected synchronized void getAccessToken(AipClientConfiguration config) {
        if (!needAuth()) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(String.format("app[%s] no need to auth", this.appId));
            }
            return;
        }
        JSONObject res = DevAuth.oauth(aipKey, aipToken, config);
        if (res == null) {
            LOGGER.warn("oauth get null response");
            return;
        }
        if (!res.isNull("access_token")) {
            // openAPI璁よ瘉鎴愬姛
            state.transfer(true);
            accessToken = res.getString("access_token");

            LOGGER.info("get access_token success. current state: " + state.toString());
            Integer expireSec = res.getInt("expires_in");
            Calendar c = Calendar.getInstance();
            c.add(Calendar.SECOND, expireSec);
            expireDate = c;
            // isBceKey.set(false);
            // 楠岃瘉鎺ュ彛鏉冮檺
            String[] scope = res.getString("scope").split(" ");
            boolean hasRight = false;
            for (String str : scope) {
                if (AipClientConst.AI_ACCESS_RIGHT.contains(str)) {
                    // 鏉冮檺楠岃瘉閫氳繃
                    hasRight = true;
                    break;
                }
            }
            state.transfer(hasRight);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("current state after check priviledge: " + state.toString());
            }
        }
        else if (!res.isNull("error_code")) {
            state.transfer(false);
            LOGGER.warn("oauth get error, current state: " + state.toString());
        }
    }

    /*
     *   闇�瑕侀噸鏂拌幏鍙朼ccess_token鐨勬潯浠讹細
     *   1. 鏄疍EV鐢ㄦ埛锛屽嵆 isBceKey涓篺alse
     *   2. isAuthorized涓篺alse锛屾垨isAuthorized涓簍rue锛屼絾褰撳墠鏃堕棿鏅氫簬expireDate鍓嶄竴澶�
     */
    protected Boolean needAuth() {
        if (isBceKey.get()) {
            return false;
        }
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DATE, 1);
        return !isAuthorized.get() || c.after(expireDate);
    }

    /*
     *  涓篋EV鍒涘缓鐨勭敤鎴峰～鍏卋ody
     */
    protected void preOperation(AipRequest request) {
        if (needAuth()) {
            getAccessToken(config);
        }

        request.setHttpMethod(HttpMethodName.POST);
        request.addHeader(Headers.CONTENT_TYPE, HttpContentType.FORM_URLENCODE_DATA);
        request.addHeader("accept", "*/*");
        request.setConfig(config);
    }

    /*
     *  涓哄叕鏈変簯鐢ㄦ埛濉厖http header锛屼紶鍏ョ殑request涓璪ody&param宸茬粡ready
     *  瀵逛簬DEV鐢ㄦ埛锛屽垯灏哸ccess_token鏀惧埌url涓�
     */
    protected void postOperation(AipRequest request) {
        if (isBceKey.get()) {
            // add aipSdk param
            request.addParam("aipSdk", "java");

            String bodyStr = request.getBodyStr();

            try {
                int len = bodyStr.getBytes(request.getContentEncoding()).length;
                request.addHeader(Headers.CONTENT_LENGTH, Integer.toString(len));
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }

            request.addHeader(Headers.CONTENT_MD5, SignUtil.md5(bodyStr, request.getContentEncoding()));

            String timestamp = Util.getCanonicalTime();
            request.addHeader(Headers.HOST, request.getUri().getHost());
            request.addHeader(Headers.BCE_DATE, timestamp);
            request.addHeader(Headers.AUTHORIZATION, CloudAuth.sign(request, this.aipKey, this.aipToken, timestamp));
        }
        else {
            request.addParam("aipSdk", "java");
            request.addParam("access_token", accessToken);
        }
    }

    /**
     * send request to server
     * @param request AipRequest object
     * @return JSONObject of server response
     */
    protected JSONObject requestServer(AipRequest request) {
        // 璇锋眰API
        AipResponse response = AipHttpClient.post(request);
        String resData = response.getBodyStr();
        Integer status = response.getStatus();
        if (status.equals(200) && !resData.equals("")) {
            try {
                JSONObject res =  new JSONObject(resData);
                if (state.getState().equals(EAuthState.STATE_POSSIBLE_CLOUD_USER)) {
                    boolean cloudAuthState = res.isNull("error_code")
                            || res.getInt("error_code") != AipClientConst.IAM_ERROR_CODE;
                    state.transfer(cloudAuthState);
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("state after cloud auth: " + state.toString());
                    }
                    if (!cloudAuthState) {
                        return Util.getGeneralError(
                                AipClientConst.OPENAPI_NO_ACCESS_ERROR_CODE,
                                AipClientConst.OPENAPI_NO_ACCESS_ERROR_MSG);
                    }
                }
                return res;
            } catch (JSONException e) {
                return Util.getGeneralError(-1, resData);
            }
        }
        else {
            LOGGER.warn(String.format("call failed! response status: %d, data: %s", status, resData));
            return AipError.NET_TIMEOUT_ERROR.toJsonResult();

        }
    }


    // getters and setters for UT
    private void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    private AtomicBoolean getIsAuthorized() {
        return isAuthorized;
    }

    private void setIsAuthorized(boolean isAuthorized) {
        this.isAuthorized.set(isAuthorized);
    }

    private AtomicBoolean getIsBceKey() {
        return isBceKey;
    }

    private void setIsBceKey(boolean isBceKey) {
        this.isBceKey.set(isBceKey);
    }

    private Calendar getExpireDate() {
        return expireDate;
    }

    private void setExpireDate(Calendar expireDate) {
        this.expireDate = expireDate;
    }

}
