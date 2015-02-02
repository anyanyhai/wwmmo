package au.com.codeka.warworlds.api;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import com.google.protobuf.Message;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nullable;

import au.com.codeka.warworlds.RealmContext;
import au.com.codeka.warworlds.Util;
import au.com.codeka.warworlds.model.Realm;

/** Encapsulates a request to the server. */
public class ApiRequest {
  private static final MediaType PROTOBUF = MediaType.parse("application/x-protobuf");

  private final String url;
  private final String method;
  @Nullable private final Message requestBody;
  private CompleteCallback completeCallback;
  @Nullable Response response;
  @Nullable private Message responseBody;
  @Nullable Map<String, List<String>> extraHeaders;

  private ApiRequest(String url, String method, @Nullable Message requestBody,
      @Nullable Map<String, List<String>> extraHeaders,
      @Nullable CompleteCallback completeCallback) {
    this.url = url;
    this.method = method;
    this.requestBody = requestBody;
    this.extraHeaders = extraHeaders;
    this.completeCallback = completeCallback;
  }

  /** Builds the OkHttp request for this request. */
  Request buildOkRequest() {
    Realm realm = RealmContext.i.getCurrentRealm();
    Request.Builder builder = new Request.Builder()
        .url(realm.getBaseUrl().resolve(url).toString())
        .method(method, convertRequestBody())
        .addHeader("User-Agent", "wwmmo/" + Util.getVersion());
    if (extraHeaders != null) {
      for (String headerName : extraHeaders.keySet()) {
        for (String headerValue : extraHeaders.get(headerName)) {
          builder.addHeader(headerName, headerValue);
        }
      }
    }
    if (realm.getAuthenticator().isAuthenticated()) {
      builder.addHeader("Cookie", realm.getAuthenticator().getAuthCookie());
    }
    builder.tag(this);
    return builder.build();
  }

  @SuppressWarnings("unchecked")
  public <T> T body(Class<? extends Message> responseClass) {
    if (responseBody == null) {
      responseBody = RequestManager.parseResponse(response, responseClass);
    }
    return (T) responseBody;
  }

  /**
   * Returns the body of the response, as a String. If the Content-Type isn't text/* then null is
   * returned instead.
   */
  public String bodyString() {
    try {
      return response.body().string();
    } catch (IOException e) {
      return null;
    }
  }

  /**
   * Returns the body of the response, as a {@link Bitmap}. If the Content-Type isn't image/*, then
   * null is returned instead.
   */
  public Bitmap bodyBitmap() {
    if (!response.body().contentType().type().equals("image")) {
      return null;
    }
    return BitmapFactory.decodeStream(response.body().byteStream());
  }

  void handleResponse(Response response) {
    this.response = response;

    // Call the callback, if there is one, on the main thread
    if (completeCallback != null) {
      new Handler(Looper.getMainLooper()).post(new Runnable() {
        @Override
        public void run() {
          completeCallback.onRequestComplete(ApiRequest.this);
        }
      });
    }
  }

  private RequestBody convertRequestBody() {
    if (requestBody == null) {
      return null;
    }
    return RequestBody.create(PROTOBUF, requestBody.toByteArray());
  }

  public interface CompleteCallback {
    void onRequestComplete(ApiRequest request);
  }

  public static class Builder {
    private String url;
    private String method;
    @Nullable private Message requestBody;
    @Nullable private Map<String, List<String>> extraHeaders;
    @Nullable private CompleteCallback completeCallback;

    public Builder(String url, String method) {
      this.url = url;
      this.method = method;
    }

    public Builder body(Message body) {
      requestBody = body;
      return this;
    }

    public Builder header(String name, String value) {
      if (extraHeaders == null) {
        extraHeaders = new TreeMap<>();
      }
      List<String> values = extraHeaders.get(name);
      if (values == null) {
        values = new ArrayList<>();
        extraHeaders.put(name, values);
      }
      values.add(value);
      return this;
    }

    /**
     * Sets the callback that is called when the request completes. The callback will always be
     * called on the UI thread.
     */
    public Builder completeCallback(CompleteCallback completeCallback) {
      this.completeCallback = completeCallback;
      return this;
    }

    public ApiRequest build() {
      return new ApiRequest(url, method, requestBody, extraHeaders, completeCallback);
    }
  }
}
