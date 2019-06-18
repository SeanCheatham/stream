package com.seancheatham.stream.server.av

import java.util.function.Supplier

import akka.actor.{ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import org.opencv.core._
import org.opencv.dnn._
import org.opencv.imgproc
import org.opencv.imgproc.Imgproc

import scala.collection.concurrent.TrieMap

object Styler {

  val default: ThreadLocal[Styler] =
    ThreadLocal.withInitial(new Supplier[Styler] {
      override def get(): Styler = new Styler()
    })
}

object NeuralStyleTransfer {

  val width: Int = 320 * 2
  val height: Int = 180 * 2


  val size: Size = new Size(width, height)
  val meanScalar: Scalar = new Scalar(103.939, 116.779, 123.680)
  val mean: Mat =
    new Mat(height, width, CvType.CV_8UC3, meanScalar)
}

class Styler() {

  def mergeLayers(layers: TraversableOnce[Mat]): Mat = {
    val lStream = layers.toStream
    val base = Mat.ones(lStream.head.cols, lStream.head.rows, CvType.CV_8UC4)
    layers.foreach(m => m.copyTo(base))
    base
  }

  def resize(mat: Mat, width: Int, height: Int): Unit = {
    imgproc.Imgproc.resize(mat, mat, new Size(width, height))
  }

  def mergeAlphaLayers(base: Mat, foreground: Mat): Mat = {
    val out = new Mat(base.size(), base.`type`())
    Core.add(base, foreground, out)
    out
  }

  /**
    * Overlays a source image onto the given canvas at the given coordinates.
    *
    * @param mat    a 4-channel source image (the overlay)
    * @param canvas a 3-channel background/canvas image
    * @param x      The x-coordinate on the canvas to start the placement
    * @param y      The y-coordinate on the canvas to start the placement
    */
  def place(mat: Mat, canvas: Mat, x: Int, y: Int): Unit = {
    val cWidth = canvas.width
    val cHeight = canvas.height

    if (mat.`type`() == CvType.CV_8UC4) {
      (0 until mat.height).foreach(i =>
        (0 until mat.width).foreach(j =>
          if (x + i < cHeight && y + j < cWidth) {
            val matBytes = mat.get(i, j)
            val alpha: Double = matBytes(3) / 255.0
            val canvasBytes = canvas.get(x + i, y + j)
            val newBytes = new Array[Double](3)
            (0 until 3).foreach(idx =>
              newBytes.update(idx, alpha * matBytes(idx) + (1 - alpha) * canvasBytes(idx))
            )
            canvas.put(x + i, y + j, newBytes: _*)
          }
        )
      )
    } else if (mat.width() == canvas.width() && mat.height() == canvas.height()) {
      mat.copyTo(canvas)
    }
    else {

      val safeX = x.min(canvas.width).max(0)
      val safeY = y.min(canvas.height).max(0)

      val safeWidth = (safeX + mat.cols).min(canvas.cols)
      val safeHeight = (safeY + mat.rows).min(canvas.rows)

      val dest = new Rect(
        new Point(safeX, safeY),
        new Size(safeWidth, safeHeight)
      )

      val baseSubmat =
        canvas.submat(dest)

      if (baseSubmat.isSubmatrix)
        mat.copyTo(baseSubmat)
      else
        mat.copyTo(canvas)

    }
  }

  /**
    * Create a new Mat that is black and opaque
    */
  def blank(width: Int, height: Int): Mat =
    opaque(Mat.zeros(height, width, CvType.CV_8UC4))

  def to3Channel(mat: Mat): Unit =
    mat.convertTo(mat, CvType.CV_8UC3)

  def to4Channel(mat: Mat): Unit =
    mat.convertTo(mat, CvType.CV_8UC4)

  /**
    * Mark the alpha channel as solid/1.0
    */
  def opaque(mat: Mat): Mat = {
    import java.util
    val channels: util.List[Mat] = new util.ArrayList[Mat](4)
    Core.split(mat, channels)
    Mat.ones(mat.size, CvType.CV_8UC1).copyTo(channels.get(3))
    mat
  }

  def grey(mat: Mat): Mat = {
    if (mat.channels() == 1) {
      mat
    } else {
      val greyMat = {
        val (rows, cols) = (mat.rows(), mat.cols())
        new Mat(rows, cols, CvType.CV_8U)
      }
      imgproc.Imgproc.cvtColor(mat, mat, imgproc.Imgproc.COLOR_BGR2GRAY, 1)
      greyMat
    }
  }

  def blur(mat: Mat, width: Int = 4, height: Int = 4): Mat = {
    imgproc.Imgproc.blur(mat, mat, new Size(width, height))
    mat
  }

  def style(mat: Mat, net: Net): Unit = {
    import java.util

    import NeuralStyleTransfer._
    import org.opencv.core.{Core, CvType}
    import org.opencv.dnn.Dnn

    val originalSize = mat.size()

    imgproc.Imgproc.resize(mat, mat, new Size(width, height))
    Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGB2BGR)

    val im = Dnn.blobFromImage(mat, 1.0, size, meanScalar, false, false)
    net.setInput(im)
    val out = net.forward

    val b = out.col(0).reshape(1, height)
    val g = out.col(1).reshape(1, height)
    val r = out.col(2).reshape(1, height)

    b.convertTo(b, CvType.CV_8UC1)
    g.convertTo(g, CvType.CV_8UC1)
    r.convertTo(r, CvType.CV_8UC1)
    val chan = new util.ArrayList[Mat](3)
    chan.add(b)
    chan.add(g)
    chan.add(r)

    Core.merge(chan, mat)
    Core.add(mat, mean, mat)
    imgproc.Imgproc.resize(mat, mat, originalSize)
    out.release()
    b.release()
    g.release()
    r.release()
  }

}

class DnnManager(system: ActorSystem) extends Extension {

  private val dnnModelsPath = system.settings.config.getString("tm.server.dnn.models.path")

  private val dnns: TrieMap[String, Net] = TrieMap.empty

  def load(name: String): Net = dnns.getOrElseUpdate(
    name,
    Dnn.readNetFromTorch(dnnModelsPath + "/" + name)
  )

}

object DnnManager extends ExtensionId[DnnManager] with ExtensionIdProvider {
  override def createExtension(system: ExtendedActorSystem): DnnManager = new DnnManager(system)

  override def lookup(): ExtensionId[_ <: Extension] = this
}