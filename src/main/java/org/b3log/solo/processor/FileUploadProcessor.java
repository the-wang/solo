package org.b3log.solo.processor;

import jodd.io.FileUtil;
import jodd.io.upload.FileUpload;
import jodd.io.upload.MultipartStreamParser;
import jodd.io.upload.impl.MemoryFileUploadFactory;
import jodd.net.MimeTypes;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.DateFormatUtils;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.logging.Level;
import org.b3log.latke.logging.Logger;
import org.b3log.latke.model.Role;
import org.b3log.latke.model.User;
import org.b3log.latke.servlet.HttpMethod;
import org.b3log.latke.servlet.RequestContext;
import org.b3log.latke.servlet.annotation.RequestProcessing;
import org.b3log.latke.servlet.annotation.RequestProcessor;
import org.b3log.latke.util.URLs;
import org.b3log.solo.SoloServletListener;
import org.b3log.solo.util.Solos;
import org.json.JSONObject;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * File upload processor.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding</a>
 * @author <a href="https://github.com/hzchendou">hzchendou</a>
 * @version 1.0.2.4, Feb 6, 2019
 * @since 2.8.0
 */
@RequestProcessor
public class FileUploadProcessor {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(FileUploadProcessor.class);

    static {
        final File file = new File(Solos.UPLOAD_DIR_PATH);
        if (!FileUtil.isExistingFolder(file)) {
            try {
                FileUtil.mkdirs(Solos.UPLOAD_DIR_PATH);
            } catch (IOException ex) {
                LOGGER.log(Level.ERROR, "Init upload dir error", ex);

                System.exit(-1);
            }
        }

        LOGGER.info("Uses dir [" + file.getAbsolutePath() + "] for saving files uploaded");
    }

    /**
     * Gets file by the specified URL.
     *
     * @param context the specified context
     */
    @RequestProcessing(value = "/upload/file/{yyyy}/{MM}/{file}", method = HttpMethod.GET)
    public void getFile(final RequestContext context) {
        final String uri = context.requestURI();
        String key = StringUtils.substringAfter(uri, "/upload/");
        key = StringUtils.substringBeforeLast(key, "?"); // Erase Qiniu template
        key = StringUtils.substringBeforeLast(key, "?"); // Erase Qiniu template

        String path = Solos.UPLOAD_DIR_PATH + key;
        path = URLs.decode(path);

        try {
            if (!FileUtil.isExistingFile(new File(path)) ||
                    !FileUtil.isExistingFolder(new File(Solos.UPLOAD_DIR_PATH)) ||
                    !new File(path).getCanonicalPath().startsWith(new File(Solos.UPLOAD_DIR_PATH).getCanonicalPath())) {
                context.sendError(HttpServletResponse.SC_NOT_FOUND);

                return;
            }
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Checks path [" + path + "] failed", e);
            context.sendError(HttpServletResponse.SC_NOT_FOUND);

            return;
        }

        byte[] data;
        try {
            data = IOUtils.toByteArray(new FileInputStream(path));
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Reads input stream failed: " + e.getMessage());
            context.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);

            return;
        }
        final HttpServletRequest req = context.getRequest();
        final String ifNoneMatch = req.getHeader("If-None-Match");
        final String etag = "\"" + DigestUtils.md5Hex(new String(data)) + "\"";

        context.addHeader("Cache-Control", "public, max-age=31536000");
        context.addHeader("ETag", etag);
        context.setHeader("Server", "Latke Static Server (v" + SoloServletListener.VERSION + ")");
        final String ext = StringUtils.substringAfterLast(path, ".");
        final String mimeType = MimeTypes.getMimeType(ext);
        context.addHeader("Content-Type", mimeType);

        if (etag.equals(ifNoneMatch)) {
            context.setStatus(HttpServletResponse.SC_NOT_MODIFIED);

            return;
        }

        final HttpServletResponse response = context.getResponse();
        try (final OutputStream output = response.getOutputStream()) {
            IOUtils.write(data, output);
            output.flush();
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Writes output stream failed: " + e.getMessage());
        }
    }

    /**
     * Uploads file.
     *
     * @param context the specified context
     */
    @RequestProcessing(value = "/upload", method = HttpMethod.POST)
    public void uploadFile(final RequestContext context) {
        final JSONObject result = new JSONObject();
        context.renderJSONPretty(result);
        result.put(Keys.CODE, -1);
        result.put(Keys.MSG, "");

        final HttpServletRequest request = context.getRequest();
        if (!Solos.isLoggedIn(context)) {
            context.sendError(HttpServletResponse.SC_UNAUTHORIZED);

            return;
        }

        final JSONObject currentUser = Solos.getCurrentUser(context.getRequest(), context.getResponse());
        if (Role.VISITOR_ROLE.equals(currentUser.optString(User.USER_ROLE))) {
            context.sendError(HttpServletResponse.SC_FORBIDDEN);

            return;
        }

        final int maxSize = 1024 * 1024 * 10;
        final MultipartStreamParser parser = new MultipartStreamParser(new MemoryFileUploadFactory().setMaxFileSize(maxSize));
        try {
            parser.parseRequestStream(request.getInputStream(), "UTF-8");
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Parses request stream failed: " + e.getMessage());
            context.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);

            return;
        }

        final List<String> errFiles = new ArrayList<>();
        final Map<String, String> succMap = new LinkedHashMap<>();
        final FileUpload[] files = parser.getFiles("file[]");

        for (int i = 0; i < files.length; i++) {
            final FileUpload file = files[i];
            final String originalName = Solos.sanitizeFilename(file.getHeader().getFileName());
            try {
                String suffix = StringUtils.substringAfterLast(originalName, ".");
                final String contentType = file.getHeader().getContentType();
                if (StringUtils.isBlank(suffix)) {
                    String[] exts = MimeTypes.findExtensionsByMimeTypes(contentType, false);
                    if (null != exts && 0 < exts.length) {
                        suffix = exts[0];
                    } else {
                        suffix = StringUtils.substringAfter(contentType, "/");
                    }
                }

                final String name = StringUtils.substringBeforeLast(originalName, ".");
                final String uuid = StringUtils.substring(UUID.randomUUID().toString().replaceAll("-", ""), 0, 8);
                String fileName = name + '-' + uuid + "." + suffix;
                fileName = genFilePath(fileName);

                final Path path = Paths.get(Solos.UPLOAD_DIR_PATH, fileName);
                path.getParent().toFile().mkdirs();
                try (final OutputStream output = new FileOutputStream(Solos.UPLOAD_DIR_PATH + fileName);
                     final InputStream input = file.getFileInputStream()) {
                    IOUtils.copy(input, output);
                }
                succMap.put(originalName, Latkes.getServePath() + "/upload/" + fileName);
            } catch (final Exception e) {
                LOGGER.log(Level.WARN, "Uploads file failed: " + e.getMessage());

                errFiles.add(originalName);
            }
        }

        final JSONObject data = new JSONObject();
        data.put("errFiles", errFiles);
        data.put("succMap", succMap);
        result.put("data", data);
        result.put(Keys.CODE, 0);
        result.put(Keys.MSG, "");
    }

    /**
     * Generates upload file path for the specified file name.
     *
     * @param fileName the specified file name
     * @return "yyyy/MM/fileName"
     */
    private static String genFilePath(final String fileName) {
        final String date = DateFormatUtils.format(System.currentTimeMillis(), "yyyy/MM");

        return "file/" + date + "/" + fileName;
    }
}