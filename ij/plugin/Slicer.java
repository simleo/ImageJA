package ij.plugin;
import ij.*;
import ij.process.*;
import ij.gui.*;
import ij.measure.*;
import ij.plugin.filter.RGBStackSplitter;
import ij.util.Tools;
import java.awt.*;
import java.awt.event.*;
import java.util.*;

/** Implements the Image/Stacks/Reslice command. Known shortcomings: 
	for FREELINE or POLYLINE ROI, spatial calibration is ignored: 
	the image is sampled at constant _pixel_ increments (distance 1), so 
	(if y/x aspect ratio != 1 in source image) one dimension in the output is not
	homogeneous (i.e. pixelWidth not the same everywhere).
*/
public class Slicer implements PlugIn, TextListener, ItemListener {

	private static final String[] starts = {"Top", "Left", "Bottom", "Right"};
	private static String startAt = starts[0];
	private static boolean rotate;
	private static boolean flip;
	private boolean nointerpolate;
	private double sampleSpacing;
	private String sampleUnit;
	private double outputZSpacing = 1.0;
	private int outputSlices = 1;
	private boolean noRoi;
	private boolean rgb;
	private Vector fields, checkboxes;
	private Label message;
	private ImagePlus imp;
	private double gx1, gy1, gx2, gy2, gLength;

	// Variables used by getIrregularProfile and doIrregularSetup
	private int n;
	private double[] x;
	private	double[] y;
	private int xbase;
	private int ybase;
	private double length;
	private double[] segmentLengths;
	private double[] dx;
	private double[] dy;

	public void run(String arg) {
		 imp = WindowManager.getCurrentImage();
		 if (imp==null) {
				IJ.noImage();
				return;
		 }
		 int stackSize = imp.getStackSize();
		 Roi roi = imp.getRoi();
		 int roiType = roi!=null?roi.getType():0;
		 // stack required except for ROI = none or RECT
		 if (stackSize<2 && roi!=null && roiType!=Roi.RECTANGLE) {
				IJ.error("Reslice...", "Stack required");
				return;
		 }
		 // permissible ROI types: none,RECT,*LINE
		 if (roi!=null && roiType!=Roi.RECTANGLE && roiType!=Roi.LINE && roiType!=Roi.POLYLINE && roiType!=Roi.FREELINE) {
				IJ.error("Reslice...", "Line or rectangular selection required");
				return;
		 }
		 double savePixelDepth = imp.getCalibration().pixelDepth;
		 if (!showDialog(imp))
				return;
		 long startTime = System.currentTimeMillis();
		 ImagePlus imp2 = null;
		 rgb = imp.getType()==ImagePlus.COLOR_RGB;
		 imp2 = reslice(imp);
		 if (imp2==null)
				return;
		 ImageProcessor ip = imp.getProcessor();
		 double min = ip.getMin();
		 double max = ip.getMax();
		 if (!rgb) imp2.getProcessor().setMinAndMax(min, max);
		 imp2.show();
		 if (noRoi)
				imp.killRoi();
		else
				imp.draw();
		if (savePixelDepth!=1.0)
			imp.getCalibration().pixelDepth = savePixelDepth;
		IJ.showStatus(IJ.d2s(((System.currentTimeMillis()-startTime)/1000.0),2)+" seconds");
	}

	public ImagePlus reslice(ImagePlus imp) {
		 ImagePlus imp2;
		 Roi roi = imp.getRoi();
		 int roiType = roi!=null?roi.getType():0;
		 Calibration origCal = imp.getCalibration();
		 if (nointerpolate) {// temporarily clear spatial calibration
				Calibration tmpCal = origCal.copy();
				tmpCal.pixelWidth = 1.0;
				tmpCal.pixelHeight = 1.0;
				tmpCal.pixelDepth = 1.0;
				imp.setCalibration(tmpCal);
				outputZSpacing = 1.0;
		 }
		 if (roi==null || roiType==Roi.RECTANGLE || roiType==Roi.LINE) {
				imp2 = resliceRectOrLine(imp);
		 } else {// we assert roiType==Roi.POLYLINE || roiType==Roi.FREELINE
				String status = imp.getStack().isVirtual()?"":null;
				IJ.showStatus("Reslice...");
				ImageProcessor ip2 = getSlice(imp, 0.0, 0.0, 0.0, 0.0, status);
				imp2 = new ImagePlus("Reslice of "+imp.getShortTitle(), ip2);
		 }
		 if (nointerpolate) {// restore calibration
				imp.setCalibration(origCal);
		 }
		 // create Calibration for new stack
		 // start from previous cal and swap appropriate fields
		 imp2.setCalibration(imp.getCalibration());
		 Calibration cal = imp2.getCalibration();
		 // along which direction did we slice ? (important if AR != 1)
		 if (roi==null || roiType==Roi.RECTANGLE) {
				if (startAt.equals(starts[0]) || startAt.equals(starts[2])) {
					// sliced vertically (from top or bottom)
					sampleSpacing = origCal.pixelWidth;
					// sampleUnit = origCal.getWidthUnit();
					// Z <- Y
					cal.pixelDepth = origCal.pixelHeight;
					cal.zOrigin = origCal.yOrigin;
					// cal.setDepthUnit(origCal.getHeightUnit());
				} else {
					// sliced horizontally (from left or right)
					sampleSpacing = origCal.pixelHeight;
					// sampleUnit = origCal.getHeightUnit();
					// Z <- X
					cal.pixelDepth = origCal.pixelWidth;
					cal.zOrigin = origCal.xOrigin;
					// cal.setDepthUnit(origCal.getWidthUnit());
				}
		 } else if (roiType==Roi.LINE) {
				Line l = (Line)roi;
				double dx = l.x2 - l.x1;
				double dy = l.y2 - l.y1;
				if (dy == 0) {// horizontal line
					sampleSpacing = origCal.pixelWidth;
					// sampleUnit = origCal.getWidthUnit();
					// Z <- Y
					cal.pixelDepth = origCal.pixelHeight;
					cal.zOrigin = origCal.yOrigin;
					// cal.setDepthUnit(origCal.getHeightUnit());
				} else if (dx == 0) {// vertical line
					sampleSpacing = origCal.pixelHeight;
					// sampleUnit = origCal.getHeightUnit();
					// Z <- X
					cal.pixelDepth = origCal.pixelWidth;
					cal.zOrigin = origCal.xOrigin;
					// cal.setDepthUnit(origCal.getWidthUnit());
				} else /* if (origCal.getHeightUnit().equals(origCal.getWidthUnit())) */ {
				// units identical: we can measure lengths of oblique lines
					double nrm = Math.sqrt(dx*dx + dy*dy);
					double w = origCal.pixelWidth;
					double h = origCal.pixelHeight;
					sampleSpacing = Math.sqrt(dx*dx*w*w+dy*dy*h*h)/nrm;
					// sampleUnit = origCal.getHeightUnit();
					cal.pixelDepth = Math.sqrt(dy*dy*w*w+dx*dx*h*h)/nrm;
					cal.zOrigin = 0.0;
					// cal.setDepthUnit(sampleUnit);
				//} else {// incomensurable axis
				//		sampleSpacing = 1.0;
				//		sampleUnit = "pixel";
				//		cal.pixelDepth = 1.0;
				//		cal.zOrigin = 0.0;
				//		cal.setDepthUnit("pixel");
				}
		 } else {
				// aspect ratio 1 & units identical ?
				if (/*origCal.getHeightUnit().equals(origCal.getWidthUnit()) &&*/
					origCal.pixelHeight == origCal.pixelWidth) {
					sampleSpacing = origCal.pixelHeight;
					// sampleUnit = origCal.getHeightUnit();
				} else {
					sampleSpacing = 1.0;
					sampleUnit = "pixel";
				}
				// (POLY|FREE)LINE ROI: single slice, Z has no meaning
				cal.pixelDepth = 1.0;
				cal.zOrigin = 0.0;
				// cal.setDepthUnit("pixel");
		 }
		 // X <- sampleSpacing
		 cal.pixelWidth = sampleSpacing;
		 cal.xOrigin = 0.0;
		 //cal.setWidthUnit(sampleUnit);
		 // Y <- Z
		 cal.pixelHeight = origCal.pixelDepth;
		 cal.yOrigin = origCal.zOrigin;
		 // cal.setHeightUnit(origCal.getDepthUnit());
		 if (!nointerpolate) {
				cal.pixelDepth = outputZSpacing*cal.pixelDepth;
		 }
		 // if rotated flip X and Y
		 double swapDouble1, swapDouble2;
		 String swapString;
		 if (rotate) {
				// save X info
				swapDouble1 = cal.pixelWidth;
				swapDouble2 = cal.xOrigin;
				// swapString = cal.getWidthUnit();
				// X <- Y
				cal.pixelWidth = cal.pixelHeight;
				cal.xOrigin = cal.yOrigin;
				// cal.setWidthUnit(cal.getHeightUnit());
				// Y <- X (saved)
				cal.pixelHeight = swapDouble1;
				cal.yOrigin = swapDouble2;
				// cal.setHeightUnit(swapString);
		 }
		 return imp2;
	}

	boolean showDialog(ImagePlus imp) {
		 Calibration cal = imp.getCalibration();
		 String units = cal.getUnits();
		 if (cal.pixelWidth==0.0)
				cal.pixelWidth = 1.0;
		 double outputSpacing = cal.pixelDepth;
		 Roi roi = imp.getRoi();
		 boolean line = roi!=null && roi.getType()==Roi.LINE;
		 if (line)
				saveLineInfo(roi);
		 GenericDialog gd = new GenericDialog("Reslice");
		 gd.addNumericField("Input Z Spacing ("+units+"):", cal.pixelDepth, 3);
		 gd.addNumericField("Output Z Spacing ("+units+"):", outputSpacing, 3);
		 if (line)
				gd.addNumericField("Slice Count:", outputSlices, 0);
		 else
				gd.addChoice("Start At:", starts, startAt);
		 gd.addCheckbox("Flip Vertically", flip);
		 gd.addCheckbox("Rotate 90 Degrees", rotate);
		 gd.addCheckbox("Avoid Interpolation", nointerpolate);
		 gd.addMessage(getSize(cal.pixelDepth,outputSpacing,outputSlices)+"				");
		 fields = gd.getNumericFields();
		 for (int i=0; i<fields.size(); i++)
				((TextField)fields.elementAt(i)).addTextListener(this);
		checkboxes = gd.getCheckboxes();
			((Checkbox)checkboxes.elementAt(2)).addItemListener(this);
		 message = (Label)gd.getMessage();
		 gd.showDialog();
		 if (gd.wasCanceled())
				return false;
		 cal.pixelDepth = gd.getNextNumber();
		 if (cal.pixelDepth==0.0) cal.pixelDepth = 1.0;
		 /* the following line should use pixelHeight if reslice is
			* from top/bottom */
		 //if (startAt.equals(starts[0]) || startAt.equals(starts[2])) {
		 //	outputZSpacing = gd.getNextNumber()/cal.pixelHeight;
		 //else
		 outputZSpacing = gd.getNextNumber()/cal.pixelWidth;
		 if (line) {
				outputSlices = (int)gd.getNextNumber();
				imp.setRoi(roi);
		 } else
				startAt = gd.getNextChoice();
		 flip = gd.getNextBoolean();
		 rotate = gd.getNextBoolean();
		 nointerpolate = gd.getNextBoolean();
		return true;
	}

	void saveLineInfo(Roi roi) {
		 Line line = (Line)roi;
		 gx1 = line.x1;
		 gy1 = line.y1;
		 gx2 = line.x2;
		 gy2 = line.y2;
		 gLength = line.getRawLength();
	}

	ImagePlus resliceRectOrLine(ImagePlus imp) {
		 double x1 = 0.0;
		 double y1 = 0.0;
		 double x2 = 0.0;
		 double y2 = 0.0;
		 double xInc = 0.0;
		 double yInc = 0.0;
		 noRoi = false;

		 Roi roi = imp.getRoi();
		 if (roi==null) {
				noRoi = true;
				imp.setRoi(0, 0, imp.getWidth(), imp.getHeight());
				roi = imp.getRoi();
		 }
		 if (roi.getType()==Roi.RECTANGLE) {
				Rectangle r = roi.getBounds();
				if (startAt.equals(starts[0])) { // top
					x1 = r.x;
					y1 = r.y;
					x2 = r.x + r.width;
					y2 = r.y;
					xInc = 0.0;
					yInc = outputZSpacing;
					outputSlices =	(int)(r.height/outputZSpacing);
				} else if (startAt.equals(starts[1])) { // left
					x1 = r.x;
					y1 = r.y;
					x2 = r.x;
					y2 = r.y + r.height;
					xInc = outputZSpacing;
					yInc = 0.0;
					outputSlices =	(int)(r.width/outputZSpacing);
				} else if (startAt.equals(starts[2])) { // bottom
					x1 = r.x;
					y1 = r.y + r.height-1;
					x2 = r.x + r.width;
					y2 = r.y + r.height-1;
					xInc = 0.0;
					yInc = -outputZSpacing;
					outputSlices =	(int)(r.height/outputZSpacing);
				} else if (startAt.equals(starts[3])) { // right
					x1 = r.x + r.width-1;
					y1 = r.y;
					x2 = r.x + r.width-1;
					y2 = r.y + r.height;
					xInc = -outputZSpacing;
					yInc = 0.0;
					outputSlices =	(int)(r.width/outputZSpacing);
				}
		 } else if (roi.getType()==Roi.LINE) {
				Line line = (Line)roi;
				x1 = line.x1;
				y1 = line.y1;
				x2 = line.x2;
				y2 = line.y2;
				double dx = x2 - x1;
				double dy = y2 - y1;
				double nrm = Math.sqrt(dx*dx + dy*dy)/outputZSpacing;
				xInc = -(dy/nrm);
				yInc = (dx/nrm);
		 } else
				return null;

		 if (outputSlices==0) {
				IJ.error("Reslicer", "Output Z spacing ("+IJ.d2s(outputZSpacing,0)+" pixels) is too large.");
				return null;
		 }
		 boolean virtualStack = imp.getStack().isVirtual();
		 String status = null;
		 ImagePlus imp2 = null;
		 ImageStack stack2 = null;
		 boolean isStack = imp.getStackSize()>1;
		 IJ.resetEscape();
		 for (int i=0; i<outputSlices; i++)	{
				if (virtualStack)
					status = outputSlices>1?(i+1)+"/"+outputSlices+", ":"";
				ImageProcessor ip = getSlice(imp, x1, y1, x2, y2, status);
				if (isStack) drawLine(x1, y1, x2, y2, imp);
				if (stack2==null) {
					stack2 = createOutputStack(imp, ip);
					if (stack2==null || stack2.getSize()<outputSlices) return null; // out of memory
				}
				stack2.setPixels(ip.getPixels(), i+1);
				x1 += xInc;
				x2 += xInc;
				y1 += yInc;
				y2 += yInc;
				if (IJ.escapePressed())
					{IJ.beep(); imp.draw(); return null;}
		 }
		 return new ImagePlus("Reslice of "+imp.getShortTitle(), stack2);
	}

	ImageStack createOutputStack(ImagePlus imp, ImageProcessor ip) {
		 int bitDepth = imp.getBitDepth();
		 int w2=ip.getWidth(), h2=ip.getHeight(), d2=outputSlices;
		 int flags = NewImage.FILL_BLACK + NewImage.CHECK_AVAILABLE_MEMORY;
		 ImagePlus imp2 = NewImage.createImage("temp", w2, h2, d2, bitDepth, flags);
		 if (imp2!=null && imp2.getStackSize()==d2)
				IJ.showStatus("Reslice... (press 'Esc' to abort)");
		 if (imp2==null)
				return null;
		 else {
				ImageStack stack2 = imp2.getStack();
				stack2.setColorModel(ip.getColorModel());
				return stack2;
		 }
	}

	ImageProcessor getSlice(ImagePlus imp, double x1, double y1, double x2, double y2, String status) {
		 Roi roi = imp.getRoi();
		 int roiType = roi!=null?roi.getType():0;
		 ImageStack stack = imp.getStack();
		 int stackSize = stack.getSize();
		 ImageProcessor ip,ip2=null;
		 float[] line = null;
		 boolean ortho = (int)x1==x1&&(int)y1==y1&&x1==x2||y1==y2;
		 //IJ.log("ortho: "+ortho);
		 for (int i=0; i<stackSize; i++) {
				ip = stack.getProcessor(flip?stackSize-i:i+1);
				if (roiType==Roi.POLYLINE || roiType==Roi.FREELINE)
					line = getIrregularProfile(roi, ip);
				else if (ortho)
					line = getOrthoLine(ip, (int)x1, (int)y1, (int)x2, (int)y2, line);
				else
					line = getLine(ip, x1, y1, x2, y2, line);
				if (rotate) {
					if (i==0) ip2 = ip.createProcessor(stackSize, line.length);
					putColumn(ip2, i, 0, line, line.length);
				} else {
					if (i==0) ip2 = ip.createProcessor(line.length, stackSize);
					putRow(ip2, 0, i, line, line.length);
				}
				if (status!=null) IJ.showStatus("Slicing: "+status +i+"/"+stackSize);
		 }
		 Calibration cal = imp.getCalibration();
		 double zSpacing = cal.pixelDepth/cal.pixelWidth;
		 if (zSpacing!=1.0) {
				ip2.setInterpolate(true);
				if (rotate)
					ip2 = ip2.resize((int)(stackSize*zSpacing), line.length);
				else
					ip2 = ip2.resize(line.length, (int)(stackSize*zSpacing));
		 }
		 return ip2;
	}

	public void putRow(ImageProcessor ip, int x, int y, float[] data, int length) {
		 if (rgb) {
				for (int i=0; i<length; i++)
					ip.putPixel(x++, y, Float.floatToIntBits(data[i]));
		 } else {
				for (int i=0; i<length; i++)
					ip.putPixelValue(x++, y, data[i]);
		 }
	}

	public void putColumn(ImageProcessor ip, int x, int y, float[] data, int length) {
		 if (rgb) {
				for (int i=0; i<length; i++)
					ip.putPixel(x, y++, Float.floatToIntBits(data[i]));
		 } else {
				for (int i=0; i<length; i++)
					ip.putPixelValue(x, y++, data[i]);
		 }
	}

	float[] getIrregularProfile(Roi roi, ImageProcessor ip) {
		 if (x==null)
				doIrregularSetup(roi);
		 float[] values = new float[(int)length];
		 double leftOver = 1.0;
		 double distance = 0.0;
		 int index;
		 double oldx=xbase, oldy=ybase;
		 for (int i=0; i<n; i++) {
				double len = segmentLengths[i];
				if (len==0.0)
					continue;
				double xinc = dx[i]/len;
				double yinc = dy[i]/len;
				double start = 1.0-leftOver;
				double rx = xbase+x[i]+start*xinc;
				double ry = ybase+y[i]+start*yinc;
				double len2 = len - start;
				int n2 = (int)len2;
				//double d=0;;
				//IJ.write("new segment: "+IJ.d2s(xinc)+" "+IJ.d2s(yinc)+" "+IJ.d2s(len)+" "+IJ.d2s(len2)+" "+IJ.d2s(n2)+" "+IJ.d2s(leftOver));
				for (int j=0; j<=n2; j++) {
					index = (int)distance+j;
					if (index<values.length) {
						 if (rgb) {
								int rgbPixel = ((ColorProcessor)ip).getInterpolatedRGBPixel(rx, ry);
								values[index] = Float.intBitsToFloat(rgbPixel&0xffffff);
						 } else
								values[index] = (float)ip.getInterpolatedValue(rx, ry);
					}
					//d = Math.sqrt((rx-oldx)*(rx-oldx)+(ry-oldy)*(ry-oldy));
					//IJ.write(IJ.d2s(rx)+"	"+IJ.d2s(ry)+"	 "+IJ.d2s(d));
					//oldx = rx; oldy = ry;
					rx += xinc;
					ry += yinc;
				}
				distance += len;
				leftOver = len2 - n2;
		 }

		 return values;

	}

	void doIrregularSetup(Roi roi) {
		 n = ((PolygonRoi)roi).getNCoordinates();
		 int[] ix = ((PolygonRoi)roi).getXCoordinates();
		 int[] iy = ((PolygonRoi)roi).getYCoordinates();
		 x = new double[n];
		 y = new double[n];
		 for (int i=0; i<n; i++) {
				x[i] = ix[i];
				y[i] = iy[i];
		 }
		 if (roi.getType()==Roi.FREELINE) {
				// smooth line
				for (int i=1; i<n-1; i++) {
					x[i] = (x[i-1] + x[i] + x[i+1])/3.0+0.5;
					y[i] = (y[i-1] + y[i] + y[i+1])/3.0+0.5;
				}
		 }
		 Rectangle r = roi.getBounds();
		 xbase = r.x;
		 ybase = r.y;
		 length = 0.0;
		 double segmentLength;
		 double xdelta, ydelta;
		 segmentLengths = new double[n];
		 dx = new double[n];
		 dy = new double[n];
		 for (int i=0; i<(n-1); i++) {
				xdelta = x[i+1] - x[i];
				ydelta = y[i+1] - y[i];
				segmentLength = Math.sqrt(xdelta*xdelta+ydelta*ydelta);
				length += segmentLength;
				segmentLengths[i] = segmentLength;
				dx[i] = xdelta;
				dy[i] = ydelta;
		 }
	}

	private float[] getLine(ImageProcessor ip, double x1, double y1, double x2, double y2, float[] data) {
		 double dx = x2-x1;
		 double dy = y2-y1;
		 int n = (int)Math.round(Math.sqrt(dx*dx + dy*dy));
		 if (data==null)
				data = new float[n];
		 double xinc = dx/n;
		 double yinc = dy/n;
		 double rx = x1;
		 double ry = y1;
		 for (int i=0; i<n; i++) {
				if (rgb) {
					int rgbPixel = ((ColorProcessor)ip).getInterpolatedRGBPixel(rx, ry);
					data[i] = Float.intBitsToFloat(rgbPixel&0xffffff);
				} else
					data[i] = (float)ip.getInterpolatedValue(rx, ry);
				rx += xinc;
				ry += yinc;
		 }
		 return data;
	}

	private float[] getOrthoLine(ImageProcessor ip, int x1, int y1, int x2, int y2, float[] data) {
		 int dx = x2-x1;
		 int dy = y2-y1;
		 int n = Math.max(dx, dy);
		 if (data==null) data = new float[n];
		 int xinc = dx/n;
		 int yinc = dy/n;
		 int rx = x1;
		 int ry = y1;
		 for (int i=0; i<n; i++) {
				if (rgb) {
					int rgbPixel = ((ColorProcessor)ip).getPixel(rx, ry);
					data[i] = Float.intBitsToFloat(rgbPixel&0xffffff);
				} else
					data[i] = (float)ip.getPixelValue(rx, ry);
				rx += xinc;
				ry += yinc;
		 }
		 return data;
	}

	void drawLine(double x1, double y1, double x2, double y2, ImagePlus imp) {
		 ImageCanvas ic = imp.getCanvas();
		 if (ic==null) return;
		 Graphics g = ic.getGraphics();
		 g.setColor(Roi.getColor());
		 g.setXORMode(Color.black);
		 g.drawLine(ic.screenX((int)(x1+0.5)), ic.screenY((int)(y1+0.5)), ic.screenX((int)(x2+0.5)), ic.screenY((int)(y2+0.5)));
	}

	public void textValueChanged(TextEvent e) {
		updateSize();
	}

	public void itemStateChanged(ItemEvent e) {
		Checkbox cb = (Checkbox)checkboxes.elementAt(2);
        nointerpolate = cb.getState();
        updateSize();
	}

	void updateSize() {
		 double inSpacing = Tools.parseDouble(((TextField)fields.elementAt(0)).getText(),0.0);
		 double outSpacing = Tools.parseDouble(((TextField)fields.elementAt(1)).getText(),0.0);
		 int count = 0;
		 boolean lineSelection = fields.size()==3;
		 if (lineSelection) {
				count = (int)Tools.parseDouble(((TextField)fields.elementAt(2)).getText(), 0.0);
				if (count>0) makePolygon(count, outSpacing);
		 }
		 String size = getSize(inSpacing, outSpacing, count);
		 message.setText(size);
	}

	String getSize(double inSpacing, double outSpacing, int count) {
		 int size = getOutputStackSize(inSpacing, outSpacing, count);
		 int mem = getAvailableMemory();
		 String available = mem!=-1?" ("+mem+"MB free)":"";
		 if (message!=null)
				message.setForeground(mem!=-1&&size>mem?Color.red:Color.black);
		 if (size>0)
				return size+"MB"+available;
		 else
				return "<1MB"+available;
	}

	void makePolygon(int count, double outSpacing) {
		 int[] x = new int[4];
		 int[] y = new int[4];
		 x[0] = (int)gx1;
		 y[0] = (int)gy1;
		 x[1] = (int)gx2;
		 y[1] = (int)gy2;
		 double dx = gx2 - gx1;
		 double dy = gy2 - gy1;
		 double nrm = Math.sqrt(dx*dx + dy*dy)/outSpacing;
		 double xInc = -(dy/nrm);
		 double yInc = (dx/nrm);
		 x[2] = x[1] + (int)(xInc*count);
		 y[2] = y[1] + (int)(yInc*count);
		 x[3] = x[0] + (int)(xInc*count);
		 y[3] = y[0] + (int)(yInc*count);
		 imp.setRoi(new PolygonRoi(x, y, 4, PolygonRoi.FREEROI));
	}

	int getOutputStackSize(double inSpacing, double outSpacing, int count) {
		Roi roi = imp.getRoi();
		int width = imp.getWidth();
		int height = imp.getHeight();
		if (roi!=null) {
			Rectangle r = roi.getBounds();
			width = r.width;
			width = r.height;
		}
		int type = roi!=null?roi.getType():0;
		int stackSize = imp.getStackSize();
		double size = 0.0;
		if (type==Roi.RECTANGLE) {
			size = width*height*stackSize;
			if (outSpacing>0&&!nointerpolate) size *= inSpacing/outSpacing;
		} else
			size = gLength*count*stackSize;
		//Calibration cal = imp.getCalibration();
		//double zSpacing = inSpacing/cal.pixelWidth;
		//if (nointerpolate) zSpacing = 1.0;
		//if (zSpacing!=0.0 && zSpacing!=1.0)
		//	size *= zSpacing;
		int bits = imp.getBitDepth();
		switch (bits) {
			case 16: size*=2; break;
			case 24: case 32: size*=4; break;
		}
		return (int)Math.round(size/1048576.0);
	}

	int getAvailableMemory() {
		 long max = IJ.maxMemory();
		 if (max==0) return -1;
		 long inUse = IJ.currentMemory();
		 long available = max - inUse;
		 return (int)((available+524288L)/1048576L);
	}
}
