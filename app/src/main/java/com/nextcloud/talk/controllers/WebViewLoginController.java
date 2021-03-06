/*
 *
 *   Nextcloud Talk application
 *
 *   @author Mario Danic
 *   Copyright (C) 2017 Mario Danic (mario@lovelyhq.com)
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.nextcloud.talk.controllers;

import android.content.pm.ActivityInfo;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.Bundle;
import android.security.KeyChain;
import android.security.KeyChainException;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ClientCertRequest;
import android.webkit.CookieSyncManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import com.bluelinelabs.conductor.RouterTransaction;
import com.bluelinelabs.conductor.changehandler.HorizontalChangeHandler;
import com.nextcloud.talk.R;
import com.nextcloud.talk.api.helpers.api.ApiHelper;
import com.nextcloud.talk.application.NextcloudTalkApplication;
import com.nextcloud.talk.controllers.base.BaseController;
import com.nextcloud.talk.events.CertificateEvent;
import com.nextcloud.talk.models.LoginData;
import com.nextcloud.talk.persistence.entities.UserEntity;
import com.nextcloud.talk.utils.ErrorMessageHolder;
import com.nextcloud.talk.utils.bundle.BundleBuilder;
import com.nextcloud.talk.utils.bundle.BundleKeys;
import com.nextcloud.talk.utils.database.user.UserUtils;
import com.nextcloud.talk.utils.ssl.MagicTrustManager;

import org.greenrobot.eventbus.EventBus;

import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;

import autodagger.AutoInjector;
import butterknife.BindView;
import io.reactivex.disposables.Disposable;
import io.requery.Persistable;
import io.requery.reactivex.ReactiveEntityStore;

@AutoInjector(NextcloudTalkApplication.class)
public class WebViewLoginController extends BaseController {

    public static final String TAG = "WebViewLoginController";

    private final String PROTOCOL_SUFFIX = "://";
    private final String LOGIN_URL_DATA_KEY_VALUE_SEPARATOR = ":";

    @Inject
    UserUtils userUtils;
    @Inject
    ReactiveEntityStore<Persistable> dataStore;
    @Inject
    MagicTrustManager magicTrustManager;
    @Inject
    EventBus eventBus;
    @Inject
    java.net.CookieManager cookieManager;

    @BindView(R.id.webview)
    WebView webView;

    @BindView(R.id.progress_bar)
    ProgressBar progressBar;

    private String assembledPrefix;

    private Disposable userQueryDisposable;

    private String baseUrl;
    private boolean isPasswordUpdate;

    public WebViewLoginController(String baseUrl, boolean isPasswordUpdate) {
        this.baseUrl = baseUrl;
        this.isPasswordUpdate = isPasswordUpdate;
    }

    public WebViewLoginController(Bundle args) {
        super(args);
    }

    @Override
    protected View inflateView(@NonNull LayoutInflater inflater, @NonNull ViewGroup container) {
        return inflater.inflate(R.layout.controller_web_view_login, container, false);
    }

    @Override
    protected void onViewBound(@NonNull View view) {
        super.onViewBound(view);
        NextcloudTalkApplication.getSharedApplication().getComponentApplication().inject(this);

        if (getActivity() != null) {
            getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }

        if (getActionBar() != null) {
            getActionBar().hide();
        }

        assembledPrefix = getResources().getString(R.string.nc_talk_login_scheme) + PROTOCOL_SUFFIX + "login/";

        webView.getSettings().setAllowFileAccess(false);
        webView.getSettings().setAllowFileAccessFromFileURLs(false);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setUserAgentString(ApiHelper.getUserAgent());
        webView.getSettings().setSaveFormData(false);
        webView.getSettings().setSavePassword(false);
        webView.getSettings().setRenderPriority(WebSettings.RenderPriority.HIGH);
        webView.clearCache(true);
        webView.clearFormData();
        webView.clearHistory();

        CookieSyncManager.createInstance(getActivity());
        android.webkit.CookieManager.getInstance().removeAllCookies(null);

        Map<String, String> headers = new HashMap<>();
        headers.put("OCS-APIRequest", "true");

        webView.setWebViewClient(new WebViewClient() {
            private boolean basePageLoaded;

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith(assembledPrefix)) {
                    parseAndLoginFromWebView(url);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!basePageLoaded) {
                    if (progressBar != null) {
                        progressBar.setVisibility(View.GONE);
                    }

                    if (webView != null) {
                        webView.setVisibility(View.VISIBLE);
                    }
                    basePageLoaded = true;
                }

                super.onPageFinished(view, url);
            }

            @Override
            public void onReceivedClientCertRequest(WebView view, ClientCertRequest request) {
                String host = null;

                try {
                    URL url = new URL(webView.getUrl());
                    host = url.getHost();
                } catch (MalformedURLException e) {
                    Log.d(TAG, "Failed to create url");
                }

                KeyChain.choosePrivateKeyAlias(getActivity(), alias -> {
                    try {
                        if (alias != null) {
                            PrivateKey privateKey = KeyChain.getPrivateKey(getActivity(), alias);
                            X509Certificate[] certificates = KeyChain.getCertificateChain(getActivity(), alias);
                            request.proceed(privateKey, certificates);
                        } else {
                            request.cancel();
                        }
                    } catch (KeyChainException e) {
                        Log.e(TAG, "Failed to get keys via keychain exception");
                        request.cancel();
                    } catch (InterruptedException e) {
                        Log.e(TAG, "Failed to get keys due to interruption");
                        request.cancel();
                    }
                }, new String[]{"RSA"}, null, host, -1, null);
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                try {
                    SslCertificate sslCertificate = error.getCertificate();
                    Field f = sslCertificate.getClass().getDeclaredField("mX509Certificate");
                    f.setAccessible(true);
                    X509Certificate cert = (X509Certificate) f.get(sslCertificate);

                    if (cert == null) {
                        handler.cancel();
                    } else {
                        try {
                            magicTrustManager.checkServerTrusted(new X509Certificate[]{cert}, "generic");
                            handler.proceed();
                        } catch (CertificateException exception) {
                            eventBus.post(new CertificateEvent(cert, magicTrustManager, handler));
                        }
                    }
                } catch (Exception exception) {
                    handler.cancel();
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
            }
        });

        webView.loadUrl(baseUrl + "/index.php/login/flow", headers);
    }


    private void dispose() {
        if (userQueryDisposable != null && !userQueryDisposable.isDisposed()) {
            userQueryDisposable.dispose();
        }

        userQueryDisposable = null;
    }

    private void parseAndLoginFromWebView(String dataString) {
        LoginData loginData = parseLoginData(assembledPrefix, dataString);

        if (loginData != null) {
            dispose();

            UserEntity currentUser = userUtils.getCurrentUser();

            ErrorMessageHolder.ErrorMessageType errorMessageType = null;
            if (currentUser != null && isPasswordUpdate &&
                    !currentUser.getUsername().equals(loginData.getUsername())) {
                ErrorMessageHolder.getInstance().setMessageType(
                        ErrorMessageHolder.ErrorMessageType.WRONG_ACCOUNT);
                getRouter().popToRoot();
            } else {

                if (!isPasswordUpdate && userUtils.getIfUserWithUsernameAndServer(loginData.getUsername(), baseUrl)) {
                    errorMessageType = ErrorMessageHolder.ErrorMessageType.ACCOUNT_UPDATED_NOT_ADDED;
                }

                if (userUtils.checkIfUserIsScheduledForDeletion(loginData.getUsername(), baseUrl)) {
                    ErrorMessageHolder.getInstance().setMessageType(
                            ErrorMessageHolder.ErrorMessageType.ACCOUNT_SCHEDULED_FOR_DELETION);
                    getRouter().popToRoot();
                }

                ErrorMessageHolder.ErrorMessageType finalErrorMessageType = errorMessageType;
                cookieManager.getCookieStore().removeAll();

                if (!isPasswordUpdate && finalErrorMessageType == null) {
                    BundleBuilder bundleBuilder = new BundleBuilder(new Bundle());
                    bundleBuilder.putString(BundleKeys.KEY_USERNAME, loginData.getUsername());
                    bundleBuilder.putString(BundleKeys.KEY_TOKEN, loginData.getToken());
                    bundleBuilder.putString(BundleKeys.KEY_BASE_URL, loginData.getServerUrl());
                    String protocol = "";

                    if (baseUrl.startsWith("http://")) {
                        protocol = "http://";
                    } else if (baseUrl.startsWith("https://")) {
                        protocol = "https://";
                    }

                    if (!TextUtils.isEmpty(protocol)) {
                        bundleBuilder.putString(BundleKeys.KEY_ORIGINAL_PROTOCOL, protocol);
                    }
                    getRouter().pushController(RouterTransaction.with(new AccountVerificationController
                            (bundleBuilder.build())).pushChangeHandler(new HorizontalChangeHandler())
                            .popChangeHandler(new HorizontalChangeHandler()));
                } else {
                    if (isPasswordUpdate) {
                        if (currentUser != null) {
                            userQueryDisposable = userUtils.createOrUpdateUser(null, null,
                                    null, null, null, true,
                                    null, currentUser.getId()).
                                    subscribe(userEntity -> {
                                                if (finalErrorMessageType != null) {
                                                    ErrorMessageHolder.getInstance().setMessageType(finalErrorMessageType);
                                                }
                                                getRouter().popToRoot();
                                            }, throwable -> dispose(),
                                            this::dispose);
                        }
                    } else {
                        if (finalErrorMessageType != null) {
                            ErrorMessageHolder.getInstance().setMessageType(finalErrorMessageType);
                        }
                        getRouter().popToRoot();

                    }
                }
            }
        }
    }

    private LoginData parseLoginData(String prefix, String dataString) {
        if (dataString.length() < prefix.length()) {
            return null;
        }

        LoginData loginData = new LoginData();

        // format is xxx://login/server:xxx&user:xxx&password:xxx
        String data = dataString.substring(prefix.length());

        String[] values = data.split("&");

        if (values.length != 3) {
            return null;
        }

        for (String value : values) {
            if (value.startsWith("user" + LOGIN_URL_DATA_KEY_VALUE_SEPARATOR)) {
                loginData.setUsername(URLDecoder.decode(
                        value.substring(("user" + LOGIN_URL_DATA_KEY_VALUE_SEPARATOR).length())));
            } else if (value.startsWith("password" + LOGIN_URL_DATA_KEY_VALUE_SEPARATOR)) {
                loginData.setToken(URLDecoder.decode(
                        value.substring(("password" + LOGIN_URL_DATA_KEY_VALUE_SEPARATOR).length())));
            } else if (value.startsWith("server" + LOGIN_URL_DATA_KEY_VALUE_SEPARATOR)) {
                loginData.setServerUrl(URLDecoder.decode(
                        value.substring(("server" + LOGIN_URL_DATA_KEY_VALUE_SEPARATOR).length())));
            } else {
                return null;
            }
        }

        if (!TextUtils.isEmpty(loginData.getServerUrl()) && !TextUtils.isEmpty(loginData.getUsername()) &&
                !TextUtils.isEmpty(loginData.getToken())) {
            return loginData;
        } else {
            return null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        dispose();
    }

    @Override
    protected void onDestroyView(@NonNull View view) {
        super.onDestroyView(view);
        if (getActivity() != null) {
            getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        }
    }

}
