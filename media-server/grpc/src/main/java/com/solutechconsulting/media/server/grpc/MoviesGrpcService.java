/*
 * Copyright 2020, Ray Elenteny
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package com.solutechconsulting.media.server.grpc;

import com.google.protobuf.Duration;
import com.google.protobuf.Empty;
import com.google.protobuf.Timestamp;
import com.solutechconsulting.media.model.Movie;
import com.solutechconsulting.media.model.protobuf.CommonProto;
import com.solutechconsulting.media.model.protobuf.MoviesGrpc;
import com.solutechconsulting.media.model.protobuf.MoviesProto;
import com.solutechconsulting.media.service.MediaService;
import io.reactivex.Flowable;
import io.vertx.core.AsyncResult;
import io.vertx.core.Vertx;
import io.vertx.core.streams.Pump;
import io.vertx.grpc.GrpcWriteStream;
import io.vertx.reactivex.FlowableHelper;
import java.time.Instant;
import java.time.ZoneOffset;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.MetadataBuilder;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.MetricType;
import org.eclipse.microprofile.metrics.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides the gRPC implementation of the {@link MediaService} movies methods as defined in {@link
 * MoviesGrpc}. The class extends and leverages the generated Vert.x base gRPC class.
 */
public class MoviesGrpcService extends MoviesGrpc.MoviesVertxImplBase {

  private static final Logger logger = LoggerFactory.getLogger(MoviesGrpcService.class.getName());

  private Vertx vertx;

  private MetricRegistry metricRegistry;
  private MediaService mediaService;

  private Timer getMoviesTimer;
  private Timer searchMoviesTimer;

  public MoviesGrpcService(Vertx vertx, MediaService mediaService, MetricRegistry metricRegistry) {
    this.mediaService = mediaService;
    this.metricRegistry = metricRegistry;
    this.vertx = vertx;

    initializeMetrics();
  }

  @Override
  public void get(Empty request, GrpcWriteStream<MoviesProto.GrpcMovie> response) {
    vertx.executeBlocking(promise -> {
      logger.debug("Invoking get...");
      streamMovieResults(mediaService.getMovies(), response, getMoviesTimer.time());
      logger.debug("get stream has started.");
      promise.complete();
    }, AsyncResult::succeeded);
  }

  @Override
  public void search(CommonProto.SearchRequest request,
      GrpcWriteStream<MoviesProto.GrpcMovie> response) {
    vertx.executeBlocking(promise -> {
      logger.debug("Invoking search... Search text: {}", request.getSearchText());
      streamMovieResults(mediaService.searchMovies(request.getSearchText()), response,
          searchMoviesTimer.time());
      logger.debug("search stream has started.");
    }, AsyncResult::succeeded);
  }

  protected void streamMovieResults(Flowable<Movie> flowable,
      GrpcWriteStream<MoviesProto.GrpcMovie> response,
      Timer.Context timerContext) {
    Pump pump =
        Pump.pump(FlowableHelper.toReadStream(flowable.doOnError(throwable -> {
          logger.error("An error occurred. Terminating stream.", throwable);
          timerContext.stop();
          response.end();
        }).doOnComplete(() -> {
          logger.debug("Movies stream complete.");
          timerContext.stop();
          response.end();
        }).map(movie -> {
          MoviesProto.GrpcMovie.Builder builder =
              MoviesProto.GrpcMovie.newBuilder().setId(movie.getId()).setTitle(
                  movie.getTitle()).setStudio(movie.getStudio()).setContentRating(
                  movie.getContentRating()).setGenres(movie.getGenres()).setTagline(
                  movie.getTagline()).setSummary(movie.getSummary()).setDirectors(
                  movie.getDirectors()).setRoles(movie.getRoles());
          movie.getCriticsRating().ifPresent(builder::setCriticsRating);
          movie.getAudienceRating().ifPresent(builder::setAudienceRating);
          movie.getYear().ifPresent(builder::setYear);

          movie.getReleaseDate().ifPresent(releaseDate -> {
            Instant instant = releaseDate.atStartOfDay().toInstant(ZoneOffset.UTC);
            builder.setReleaseDate(
                Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(
                    instant.getNano()));
          });

          builder.setDuration(
              Duration.newBuilder().setSeconds(movie.getDuration().getSeconds()).setNanos(
                  movie.getDuration().getNano()));

          return builder.build();
        })), response);
    pump.start();
  }

  /**
   * Creates implementation specific metrics. This pattern supports establishing common metrics
   * across any implementation of MediaService choosing to extend from this abstract class. It
   * provides consistency in metrics naming and documentation.
   */
  public void initializeMetrics() {
    logger.debug("Initializing service metrics...");

    String metricsPrefix = MoviesGrpcService.class.getName();
    String name = metricsPrefix + '.' + MediaService.MetricsDefinitions.GetMovies.TIMER_NAME;
    Metadata metadata =
        new MetadataBuilder().withName(name).withDisplayName(name).withType(
            MetricType.TIMER).withDescription(
            MediaService.MetricsDefinitions.GetMovies.TIMER_DESCRIPTION).build();

    getMoviesTimer = metricRegistry.timer(metadata);

    name = metricsPrefix + '.' + MediaService.MetricsDefinitions.SearchMovies.TIMER_NAME;
    metadata =
        new MetadataBuilder().withName(name).withDisplayName(name).withType(
            MetricType.TIMER).withDescription(
            MediaService.MetricsDefinitions.SearchMovies.TIMER_DESCRIPTION).build();

    searchMoviesTimer = metricRegistry.timer(metadata);

    logger.debug("Service metrics initialized.");
  }
}
