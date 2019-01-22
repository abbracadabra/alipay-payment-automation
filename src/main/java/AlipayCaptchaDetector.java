import org.bytedeco.javacpp.indexer.IntIndexer;
import org.bytedeco.javacpp.indexer.UByteIndexer;
import org.tensorflow.SavedModelBundle;
import org.tensorflow.Session;
import org.tensorflow.Tensor;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static org.bytedeco.javacpp.opencv_core.*;
import static org.bytedeco.javacpp.opencv_imgcodecs.*;
import static org.bytedeco.javacpp.opencv_imgproc.*;

public class AlipayCaptchaDetector {

    private static Session sess = null;

    static {
        SavedModelBundle bundle = SavedModelBundle.load(new File(AlipayCaptchaDetector.class.getClassLoader().getResource("buildmodel/").getFile()).getAbsolutePath(), "serve");
        sess = bundle.session();
    }

    public static String predict(String imagepath){
        Mat pic = imread(imagepath);
        MatVector mv = new MatVector();
        split(pic,mv);
        Mat green = mv.get(1);
        Mat red = mv.get(2);
        Mat im = new Mat(pic.rows(),pic.cols(),CV_8UC1);
        UByteIndexer ixm = im.createIndexer();
        UByteIndexer gix = green.createIndexer();
        UByteIndexer rix = red.createIndexer();
        for(int i=0;i<pic.rows();i++){
            for(int j=0;j<pic.cols();j++){
                int max = rix.get(i, j)>gix.get(i, j)?rix.get(i, j):gix.get(i, j);
                ixm.put(i, j, 0, 255 - max);
            }
        }

        Mat tim=null;
        MatVector contours = null;
        Mat hierarchy = null;
        List<Mat> outer = null;
        List<List<Mat>> inner = null;
        for(int thres = 230;thres<=240;thres+=4){
            tim = new Mat();
            threshold(im,tim, 230, 255, THRESH_BINARY);
            contours = new MatVector();
            hierarchy = new Mat();
            findContours(tim, contours,hierarchy,RETR_TREE  ,CHAIN_APPROX_SIMPLE);

            outer = new ArrayList<>();
            inner = new ArrayList<>();

            IntIndexer hierarchy_indexer = hierarchy.createIndexer();
            for(int i=0;i<hierarchy.cols();i++){
                if(hierarchy_indexer.get(0,i,3)==-1){//parent is none
                    outer.add(contours.get(i));
                    List<Mat> sub = new ArrayList<>();
                    for(int j=0;j<hierarchy.cols();j++){
                        if(hierarchy_indexer.get(0,j,3)== i){
                            sub.add(contours.get(j));
                        }
                    }
                    inner.add(sub);
                }
            }
            if(outer.size()==4){
                break;
            }
        }
        if(outer.size()!=4){
            return "";
        }
        im=tim;

        List<Integer> x_list= new ArrayList<>();
        List<Boolean> valid_mask= new ArrayList<>();
        for(Mat cnt : outer){
            Rect xywh = boundingRect(cnt);
            x_list.add(xywh.x());
            if(xywh.height()*xywh.width() > 50){
                valid_mask.add(true);
            }else{
                valid_mask.add(false);
            }
        }

        List<Integer> order = new ArrayList<>();
        for(int i=0;i<x_list.size();i++){
            order.add(i);
        }
        Collections.sort(order, new Comparator<Integer>() {
            @Override
            public int compare(final Integer o1, final Integer o2) {
                return Integer.compare(x_list.get(o1), x_list.get(o2));
            }
        });
        List<Boolean> valid_masktmp=new ArrayList<>();
        List<Mat> order_outer_contours = new ArrayList<>();
        List<List<Mat>> order_sub_contour = new ArrayList<>();
        for(int i : order){
            valid_masktmp.add(valid_mask.get(i));
            order_outer_contours.add(outer.get(i));
            order_sub_contour.add(inner.get(i));
        }
        valid_mask = valid_masktmp;
        for(int i=valid_mask.size()-1;i>=0;i--){
            if(valid_mask.get(i).equals(false)){
                order_outer_contours.remove(i);
                order_sub_contour.remove(i);
            }
        }

        float[][][][] ins = new float[order_outer_contours.size()][32][32][1];
        for(int i=0;i<order_outer_contours.size();i++){
            Mat imt = new Mat(pic.rows(),pic.cols(),CV_8UC1,new Scalar(0.0));
            order_sub_contour.get(i).add(order_outer_contours.get(i));
            MatVector ccs = new MatVector(order_sub_contour.get(i).toArray(new Mat[]{}));
            Mat hi = null;
            drawContours(imt, ccs, -1, new Scalar(255.0), 1, 8, hi, 2, new Point(0,0));

            //Mat mask = Mat.zeros(new Size(100,30),0).asMat();
            //threshold(imt,mask,1,255,THRESH_BINARY);
            MatVector contours1 = new MatVector();
            Mat hierarchy1 = new Mat();
            findContours(imt,contours1,hierarchy1,RETR_EXTERNAL,CHAIN_APPROX_SIMPLE);
            Rect bbox = boundingRect(contours1.get(0));
            Mat ff = new Mat(imt, bbox);
            resize(ff,ff,new Size(32,32),0,0,INTER_NEAREST);
            //threshold(ff,ff,1,255,THRESH_BINARY);
            UByteIndexer ixf = ff.createIndexer();
            for(int j=0;j<ff.rows();j++){
                for(int k=0;k<ff.cols();k++){
                    ins[i][j][k][0] = ixf.get(j,k,0)* (1.0f/255.0f);
                }
            }
        }

        Tensor<Float> imageTensor = Tensor.create(ins, Float.class);
        Tensor<Long> result = sess.runner()
                .feed("input", imageTensor)
                .fetch("output")
                .run()
                .get(0)
                .expect(Long.class);
        long[] prediction = new long[order_outer_contours.size()];
        result.copyTo(prediction);
        StringBuilder res = new StringBuilder();
        for(long n : prediction){
            if(n>=0 && n<=9){
                res.append(String.valueOf((char) (n+48)));
            }
            if(n>=10 && n<=35){
                res.append(String.valueOf((char) (n-10+65)));
            }
        }
        return res.toString();
    }

    public static void main(String[] args){
        System.out.print(predict(new File(AlipayCaptchaDetector.class.getClassLoader().getResource("alipaycaptchatmp/EPRJ.png").getFile()).getAbsolutePath()));
    }
}
