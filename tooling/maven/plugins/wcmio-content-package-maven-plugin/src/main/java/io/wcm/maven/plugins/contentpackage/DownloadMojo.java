/*
 * #%L
 * wcm.io
 * %%
 * Copyright (C) 2014 wcm.io
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package io.wcm.maven.plugins.contentpackage;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.multipart.FilePart;
import org.apache.commons.httpclient.methods.multipart.MultipartRequestEntity;
import org.apache.commons.httpclient.methods.multipart.Part;
import org.apache.commons.httpclient.methods.multipart.StringPart;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.codehaus.plexus.util.IOUtil;
import org.json.JSONObject;

/**
 * Builds and downloads a content package defined on a remote CRX or AEM system.
 */
@Mojo(name = "download", defaultPhase = LifecyclePhase.INSTALL, requiresProject = true,
requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public final class DownloadMojo extends AbstractContentPackageMojo {

  /**
   * The output file to save.
   */
  @Parameter(property = "vault.outputFile", required = true, defaultValue = "${project.build.directory}/${project.build.finalName}.zip")
  private String outputFile;

  /**
   * If set to true the package is unpacked to the directory specified by <code>unpackDirectory </code>.
   */
  @Parameter(property = "vault.unpack", defaultValue = "false")
  private boolean unpack;

  /**
   * Directory to unpack the content of the package to.
   */
  @Parameter(property = "vault.unpackDirectory", defaultValue = "${basedir}")
  private File unpackDirectory;

  /**
   * If unpack=true: delete existing content from the named directories (relative to <code>unpackDirectory</code> root)
   * before unpacking the package content, to make sure only the content from the downloaded package remains.
   */
  @Parameter
  private String[] unpackDeleteDirectories = new String[] {
      "jcr_root",
      "META-INF"
  };

  /**
   * List of regular patterns matching relative path of extracted content package. All files matching these patterns
   * are excluded when unpacking the content package.
   */
  @Parameter
  private String[] excludeFiles;

  /**
   * List of regular patterns matching node paths inside a <code>.content.xml</code> file. All nodes matching
   * theses patterns are removed from the <code>.content.xml</code> when unpacking the content package.
   */
  @Parameter
  private String[] excludeNodes;

  /**
   * List of regular patterns matching property names inside a <code>.content.xml</code> file. All properties matching
   * theses patterns are removed from the <code>.content.xml</code> when unpacking the content package.
   */
  @Parameter
  private String[] excludeProperties;

  /**
   * Downloads the files
   */
  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    if (isSkip()) {
      return;
    }

    File outputFileObject = downloadFile(getPackageFile(), this.outputFile);
    if (this.unpack) {
      unpackFile(outputFileObject);
    }
  }

  /**
   * Download content package from CRX instance
   */
  private File downloadFile(File file, String ouputFilePath) throws MojoExecutionException {
    try {
      getLog().info("Download " + file.getName() + " from " + getCrxPackageManagerUrl());

      // setup http client with credentials
      HttpClient httpClient = getCrxPackageManagerHttpClient();

      // 1st: try upload to get path of package - or otherwise make sure package def exists (no install!)
      PostMethod post = new PostMethod(getCrxPackageManagerUrl() + "/.json?cmd=upload");
      List<Part> parts = new ArrayList<Part>();
      parts.add(new FilePart("package", file));
      parts.add(new StringPart("force", "true"));
      post.setRequestEntity(new MultipartRequestEntity(parts.toArray(new Part[parts.size()]), post.getParams()));
      JSONObject response = executePackageManagerMethodJson(httpClient, post, 0);
      boolean success = response.optBoolean("success", false);
      String msg = response.optString("msg", null);
      String path = response.optString("path", null);

      // package already exists - get path from error message and continue
      if (!success && StringUtils.startsWith(msg, CRX_PACKAGE_EXISTS_ERROR_MESSAGE_PREFIX) && StringUtils.isEmpty(path)) {
        path = StringUtils.substringAfter(msg, CRX_PACKAGE_EXISTS_ERROR_MESSAGE_PREFIX);
        success = true;
      }
      if (!success) {
        throw new MojoExecutionException("Package path detection failed: " + msg);
      }

      getLog().info("Package path is: " + path + " - now rebuilding package...");

      // 2nd: build package
      PostMethod buildMethod = new PostMethod(getCrxPackageManagerUrl() + "/console.html" + path + "?cmd=build");
      executePackageManagerMethodHtml(httpClient, buildMethod, 0);

      // 3rd: download package
      String crxUrl = StringUtils.removeEnd(getCrxPackageManagerUrl(), "/crx/packmgr/service");
      GetMethod downloadMethod = new GetMethod(crxUrl + path);

      // execute download
      int httpStatus = httpClient.executeMethod(downloadMethod);
      try {
        if (httpStatus == HttpStatus.SC_OK) {

          // get response stream
          InputStream responseStream = downloadMethod.getResponseBodyAsStream();

          // delete existing file
          File outputFileObject = new File(ouputFilePath);
          if (outputFileObject.exists()) {
            outputFileObject.delete();
          }

          // write response file
          FileOutputStream fos = new FileOutputStream(outputFileObject);
          IOUtil.copy(responseStream, fos);
          fos.flush();
          responseStream.close();
          fos.close();

          getLog().info("Package downloaded to " + outputFileObject.getAbsolutePath());

          return outputFileObject;
        }
        else {
          throw new MojoExecutionException("Package download failed:\n"
              + downloadMethod.getResponseBodyAsString());
        }
      }
      finally {
        // cleanup
        downloadMethod.releaseConnection();
      }
    }
    catch (FileNotFoundException ex) {
      throw new MojoExecutionException("File not found: " + file.getAbsolutePath(), ex);
    }
    catch (HttpException ex) {
      throw new MojoExecutionException("Post method failed.", ex);
    }
    catch (IOException ex) {
      throw new MojoExecutionException("Post method failed.", ex);
    }
  }

  /**
   * Unpack content package
   */
  private void unpackFile(File file) throws MojoExecutionException, MojoFailureException {

    // initialize unpacker to validate patterns
    ContentUnpacker unpacker = new ContentUnpacker(this.excludeFiles, this.excludeNodes, this.excludeProperties);

    // validate output directory
    if (this.unpackDirectory == null) {
      throw new MojoExecutionException("No unpack directory specified.");
    }
    if (!this.unpackDirectory.exists()) {
      this.unpackDirectory.mkdirs();
    }

    // remove existing content
    if (this.unpackDeleteDirectories != null) {
      for (String directory : unpackDeleteDirectories) {
        File directoryFile = FileUtils.getFile(this.unpackDirectory, directory);
        if (directoryFile.exists()) {
          if (!deleteDirectoryWithRetries(directoryFile, 0)) {
            throw new MojoExecutionException("Unable to delete existing content from "
                + directoryFile.getAbsolutePath());
          }
        }
      }
    }

    // unpack file
    unpacker.unpack(file, this.unpackDirectory);

    getLog().info("Package unpacked to " + this.unpackDirectory.getAbsolutePath());
  }

  /**
   * Delete fails sometimes or may be blocked by an editor - give it some time to try again (max. 1 sec).
   */
  private boolean deleteDirectoryWithRetries(File directory, int retryCount) {
    if (retryCount > 100) {
      return false;
    }
    if (FileUtils.deleteQuietly(directory)) {
      return true;
    }
    else {
      try {
        Thread.sleep(10);
      }
      catch (InterruptedException ex) {
        // ignore
      }
      return deleteDirectoryWithRetries(directory, retryCount + 1);
    }
  }

}
