/*
 * Copyright 2012-2013 Continuuity,Inc. All Rights Reserved.
 */

package com.continuuity.internal.filesystem;

import com.continuuity.filesystem.Location;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;

/**
 * A concrete implementation of {@link Location} for the HDFS filesystem.
 */
final class HDFSLocation implements Location {
  private final FileSystem fs;
  private final Path path;

  /**
   * Created by the {@link HDFSLocationFactory}
   * @param fs An instance of {@link FileSystem}
   * @param path of the file.
   */
  public HDFSLocation(FileSystem fs, String path) {
    this.fs = fs;
    this.path = new Path(path);
  }

  /**
   * Created by the {@link HDFSLocationFactory}
   * @param fs An instance of {@link FileSystem}
   * @param uri of the file.
   */
  public HDFSLocation(FileSystem fs, URI uri) {
    this.fs = fs;
    this.path = new Path(uri);
  }

  /**
   * Checks if the this location exists on HDFS.
   * @return true if found; false otherwise.
   * @throws IOException
   */
  @Override
  public boolean exists() throws IOException {
    return fs.exists(path);
  }

  /**
   * @return An {@link InputStream} for this location on HDFS.
   * @throws IOException
   */
  @Override
  public InputStream getInputStream() throws IOException {
    return fs.open(path);
  }

  /**
   * @return An {@link OutputStream} for this location on HDFS.
   * @throws IOException
   */
  @Override
  public OutputStream getOutputStream() throws IOException {
    return fs.append(path);
  }

  /**
   * Appends the child to the current {@link Location} on HDFS.
   * <p>
   *   Returns a new instance of Location.
   * </p>
   * @param child to be appended to this location.
   * @return A new instance of {@link Location}
   * @throws IOException
   */
  @Override
  public Location append(String child) throws IOException {
    return new HDFSLocation(fs, child);
  }

  /**
   * @return Returns the name of the file or directory denoteed by this abstract pathname.
   */
  @Override
  public String getUri() {
    return path.getName();
  }

  /**
   * @return A {@link URI} for this location on HDFS.
   */
  @Override
  public URI toURI() {
    return path.toUri();
  }
}
