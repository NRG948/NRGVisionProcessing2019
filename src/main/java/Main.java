
/*----------------------------------------------------------------------------*/
/* Copyright (c) 2018 FIRST. All Rights Reserved.                             */
/* Open Source Software - may be modified and shared by FRC teams. The code   */
/* must be accompanied by the FIRST BSD license file in the root directory of */
/* the project.                                                               */
/*----------------------------------------------------------------------------*/

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import edu.wpi.cscore.CvSource;
import edu.wpi.cscore.MjpegServer;
import edu.wpi.cscore.UsbCamera;
import edu.wpi.cscore.VideoMode;
import edu.wpi.cscore.VideoSource;
import edu.wpi.first.cameraserver.CameraServer;
import edu.wpi.first.networktables.NetworkTableEntry;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.vision.VisionThread;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;
import edu.wpi.first.networktables.NetworkTable;

import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

/*
   JSON format:
   {
       "team": <team number>,
       "ntmode": <"client" or "server", "client" if unspecified>
       "cameras": [
           {
               "name": <camera name>
               "path": <path, e.g. "/dev/video0">
               "pixel format": <"MJPEG", "YUYV", etc>   // optional
               "width": <video mode width>              // optional
               "height": <video mode height>            // optional
               "fps": <video mode fps>                  // optional
               "brightness": <percentage brightness>    // optional
               "white balance": <"auto", "hold", value> // optional
               "exposure": <"auto", "hold", value>      // optional
               "properties": [                          // optional
                   {
                       "name": <property name>
                       "value": <property value>
                   }
               ],
               "stream": {                              // optional
                   "properties": [
                       {
                           "name": <stream property name>
                           "value": <stream property value>
                       }
                   ]
               }
           }
       ]
   }
 */

public final class Main {
  private static final Scalar BLUE_COLOR = new Scalar(255, 0, 0);
  private static final Scalar RED_COLOR = new Scalar(0, 0, 255);
  private static String configFile = "/boot/frc.json";
  private static final Scalar GREEN_COLOR = new Scalar(0, 255, 0);
  private static final Scalar PURPLE_COLOR = new Scalar(255, 0, 255);

  @SuppressWarnings("MemberName")
  public static class CameraConfig {
    public String name;
    public String path;
    public JsonObject config;
    public JsonElement streamConfig;
  }

  public static int team;
  public static boolean server;
  public static List<CameraConfig> cameraConfigs = new ArrayList<>();

  private Main() {
  }

  /**
   * Report parse error.
   */
  public static void parseError(String str) {
    System.err.println("config error in '" + configFile + "': " + str);
  }

  /**
   * Read single camera configuration.
   */
  public static boolean readCameraConfig(JsonObject config) {
    CameraConfig cam = new CameraConfig();

    // name
    JsonElement nameElement = config.get("name");
    if (nameElement == null) {
      parseError("could not read camera name");
      return false;
    }
    cam.name = nameElement.getAsString();

    // path
    JsonElement pathElement = config.get("path");
    if (pathElement == null) {
      parseError("camera '" + cam.name + "': could not read path");
      return false;
    }
    cam.path = pathElement.getAsString();

    // stream properties
    cam.streamConfig = config.get("stream");

    cam.config = config;

    cameraConfigs.add(cam);
    return true;
  }

  /**
   * Read configuration file.
   */
  @SuppressWarnings("PMD.CyclomaticComplexity")
  public static boolean readConfig() {
    // parse file
    JsonElement top;
    try {
      top = new JsonParser().parse(Files.newBufferedReader(Paths.get(configFile)));
    } catch (IOException ex) {
      System.err.println("could not open '" + configFile + "': " + ex);
      return false;
    }

    // top level must be an object
    if (!top.isJsonObject()) {
      parseError("must be JSON object");
      return false;
    }
    JsonObject obj = top.getAsJsonObject();

    // team number
    JsonElement teamElement = obj.get("team");
    if (teamElement == null) {
      parseError("could not read team number");
      return false;
    }
    team = teamElement.getAsInt();

    // ntmode (optional)
    if (obj.has("ntmode")) {
      String str = obj.get("ntmode").getAsString();
      if ("client".equalsIgnoreCase(str)) {
        server = false;
      } else if ("server".equalsIgnoreCase(str)) {
        server = true;
      } else {
        parseError("could not understand ntmode value '" + str + "'");
      }
    }

    // cameras
    JsonElement camerasElement = obj.get("cameras");
    if (camerasElement == null) {
      parseError("could not read cameras");
      return false;
    }
    JsonArray cameras = camerasElement.getAsJsonArray();
    for (JsonElement camera : cameras) {
      if (!readCameraConfig(camera.getAsJsonObject())) {
        return false;
      }
    }

    return true;
  }

  /**
   * Start running the camera.
   */
  public static VideoSource startCamera(CameraConfig config) {
    System.out.println("Starting camera '" + config.name + "' on " + config.path);
    CameraServer inst = CameraServer.getInstance();
    UsbCamera camera = new UsbCamera(config.name, config.path);
    MjpegServer server = inst.startAutomaticCapture(camera);

    Gson gson = new GsonBuilder().create();

    camera.setConfigJson(gson.toJson(config.config));
    camera.setConnectionStrategy(VideoSource.ConnectionStrategy.kKeepOpen);

    if (config.streamConfig != null) {
      server.setConfigJson(gson.toJson(config.streamConfig));
    }

    return camera;
  }

  /**
   * Main.
   */
  public static void main(String... args) {
    if (args.length > 0) {
      configFile = args[0];
    }

    // read configuration
    if (!readConfig()) {
      return;
    }

    // start NetworkTables
    NetworkTableInstance ntinst = NetworkTableInstance.getDefault();
    if (server) {
      System.out.println("Setting up NetworkTables server");
      ntinst.startServer();
    } else {
      System.out.println("Setting up NetworkTables client for team " + team);
      ntinst.startClientTeam(team);
    }

    // start cameras
    List<VideoSource> cameras = new ArrayList<>();
    for (CameraConfig cameraConfig : cameraConfigs) {
      cameras.add(startCamera(cameraConfig));
    }

    // start image processing on camera 0 if present
    if (cameras.size() >= 1) {
      CvSource processedVideo = CameraServer.getInstance().putVideo("Processed", 320, 240);

      VideoSource videoSource = cameras.get(0);
      VideoMode videoMode = videoSource.getVideoMode();
      Point imageCenter = new Point(videoMode.width / 2, videoMode.height / 2);
      VisionThread visionThread = new VisionThread(videoSource, new TargetPipeline(), pipeline -> {

        long startTime = System.nanoTime();

        boolean isCameraInverted = SmartDashboard.getBoolean("Vision/cameraInverted", false);
        ArrayList<Target> targets = new ArrayList<Target>();
        for (MatOfPoint mat : pipeline.filterContoursOutput()) {
          Target target = new Target(mat, isCameraInverted);
          targets.add(target);
        }

        boolean isOrdered = true;
        Point targetCenter = null;
        if (!targets.isEmpty()) {
          Collections.sort(targets,
              (left, right) -> (int) (left.getMinX().x - right.getMinX().x) * (isCameraInverted ? -1 : 1));
          Target.Side side = targets.get(0).getSide();

          for (int i = 1; i < targets.size(); ++i) {
            Target.Side side2 = targets.get(i).getSide();
            if (side2 == side || side2 == Target.Side.UNKOWN) {
              isOrdered = false;
              break;
            }
            side = side2;
          }
        }

        ArrayList<TargetPair> targetPairs = new ArrayList<TargetPair>();
        if (targets.size() >= 2) {
          for (int i = 0; i < targets.size() - 1; ++i) {
            Target current = targets.get(i);
            if (current.getSide() == Target.Side.LEFT) {
              Target nextTarget = targets.get(i + 1);
              if (nextTarget.getSide() == Target.Side.RIGHT) {
                TargetPair targetPair = new TargetPair(current, nextTarget);
                targetPairs.add(targetPair);
                ++i;
              }
            }
          }
        }
        Collections.sort(targetPairs, (left, right) -> (int) (Math.abs(left.getCenterOfTargets().x - imageCenter.x)
            - (Math.abs(right.getCenterOfTargets().x - imageCenter.x))));

        if (!targetPairs.isEmpty()) {
          targetCenter = targetPairs.get(0).getCenterOfTargets();
        }

        Mat image = pipeline.getImage();
        for (int i = 0; i < targets.size(); ++i) {
          Target target = targets.get(i);
          Scalar color = target.getSide() == Target.Side.LEFT ? RED_COLOR : BLUE_COLOR;
          Imgproc.fillConvexPoly(image, target.toMatOfPoint(), color);
        }
        Imgproc.circle(image, imageCenter, 5, GREEN_COLOR, -1);
        if (targetCenter != null) {
          Imgproc.circle(image, targetCenter, 10, PURPLE_COLOR, 2);
        }
        processedVideo.putFrame(image);

        String[] targetPairsJson = new String[targetPairs.size()];
        GsonBuilder builder = new GsonBuilder();
        Gson gson = builder.create();
        for (int i = 0; i < targetPairs.size(); i++) {
          targetPairsJson[i] = gson.toJson(targetPairs.get(i));
        }

        long endTime = System.nanoTime();

        SmartDashboard.putStringArray("Vision/targetPairs", targetPairsJson);
        SmartDashboard.putNumber("Vision/processTime", pipeline.getProcessTime() / 1000000.0);
        SmartDashboard.putNumber("Vision/postProcessTime", (endTime - startTime) / 1000000.0);
        SmartDashboard.putNumber("Vision/imageCenterX", imageCenter.x);
      });
      visionThread.start();
    }

    // loop forever
    for (;;) {
      try {
        Thread.sleep(10000);
      } catch (InterruptedException ex) {
        return;
      }
    }
  }
}
