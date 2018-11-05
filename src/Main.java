/*
 * pingcap_test
 * created by Arenatlx in 10/31/2018
 */


/*
 * 思路：100G的url文件，首先可能使用hash表做String到int数量的映射，需要在全局维护一个
 * 具有所有键值对的hash表，但是如果重复率很小的话，hash表的key值就已经会爆内存了。可以
 * 考虑使用全局的hash，分解成局部的hash，或者是，将全局的hash的部分存到文件中去。
 *
 * way1：将<key,val>的映射表，部分放在内存中，部分写到文件中，如同LSM-Tree，但是这样也会
 * 导致查询或者写入引入大量的disc IO，实现也比较复杂。
 *
 * way2：将全局的hash分解成局部的hash，不同的子文件不存在相同的url，然后对每个子文件构建
 * hash表，按val排序后，取出top100的<key,val>队，然后将多余hash表即使的删除，然后将这个
 * 100个<key,val>加入下一个子文件构建的hash表中,直到最后一个子文件。
 *
 * 这里我们选用way2！
 */


import java.io.*;
import java.util.*;

public class Main {
    //used to hold the sub file
    private static Map<String,File>File_Map = new HashMap<>();
    //used to quick insert url
    private static Map<String,BufferedWriter> Writer_Map = new HashMap<>();

    public static void main(String[] args) {

        File src_file = new File("test_file/test_src.txt");
        try {
            splitFile(src_file);
            reduceFile();
            clearSubFile();
        }
        catch (IOException e){
            e.printStackTrace();
            System.out.println("IO异常，请检查文件是否存在，路径是否正确");
        }
        catch (RuntimeException e){
            e.printStackTrace();
            System.out.println("源文件不符合要求");
        }

    }

    /*
     * 按每个url的特定位置的两个字符，将大文件分解成小
     * 文件，考虑均匀分布的话，可以分为26*26=676个小文件，
     * 每个文件大约150M，如果考虑符号字符在内的话，每个子
     * 文件的大小将更小。尽量选择靠后位置的字符，因为前面
     * 的域名可能相同，无法达到最大随机化分割小文件的效果。
     *
     * 可能url分布不是很均匀，可以在在局部操作之前，加测
     * 每个file文件的长度，超过600M（预留系统本身的环境
     * 和hashmap的存储）的小文件，再次进行文件分割，使用
     * url前3个前缀字符。
     *
     * 考虑每个文件150M的话，假设每个url长度为10字节
     * 150M/10B = 15728640在int范围内，所以可以用
     * integer来存。
     *
     */
    public static void splitFile(File src) throws IOException, RuntimeException{
        if(!src.isFile()||src.length()==0){
            throw new RuntimeException("文件不符合拆分要求");
        }

        //set val
        String url  = null;
        BufferedReader src_reader = new BufferedReader(new FileReader(src));

        //read the src to sub file
        while((url = src_reader.readLine())!=null&&url.length()!=0){

            //get sub file name
            String sub_str = url.substring(0,2);
            //System.out.println(sub_str);

            //store every line to corresponding sub file
            BufferedWriter sub_writer= Writer_Map.get(sub_str);
            if(sub_writer==null){
                //create new file
                File sub_file = new File(sub_str+".sub");
                //add the sub file to hash map for next reader opening
                File_Map.put(sub_str,sub_file);
                //also add to the sub writer
                sub_writer = new BufferedWriter(new FileWriter(sub_file));
                Writer_Map.put(sub_str,sub_writer);
            }
            sub_writer.write(url);
            sub_writer.newLine();
        }

        //close buffered writer in hash map
        for(String key : Writer_Map.keySet()){
            BufferedWriter sub_writer = Writer_Map.get(key);
            sub_writer.flush();
            sub_writer.close();
        }
        Writer_Map.clear();

        //src file close
        src_reader.close();
    }


    //判断每个文件的大小是否符合
    public static void judgeFile() throws RuntimeException{
        if(File_Map==null||File_Map.size()==0){
            throw new RuntimeException("子文件异常");
        }
        for(String key : File_Map.keySet()) {
            File sub_file = File_Map.get(key);
            //size in byte
            long len = sub_file.length();
            if(len>500*1024*1024){
                //split file again
            }
        }
    }

    public static void reduceFile() throws IOException,RuntimeException{
        if(File_Map==null||File_Map.size()==0){
            throw new RuntimeException("子文件异常");
        }

        Map<String,Integer> Count_Map = new HashMap<>();
        // used to store the every first 100 entry in each sub file, it will update
        // with iteration
        List<Map.Entry<String,Integer>> the_first_hundred = new ArrayList<>();

        //in sub file loop
        for(String key : File_Map.keySet()){

            // in each file
            File sub_file = File_Map.get(key);
            BufferedReader sub_reader = new BufferedReader(new FileReader(sub_file));

            //update var
            String url = null;
            Count_Map.clear();

            //read the file
            while((url = sub_reader.readLine())!=null){
                if(Count_Map.get(url)==null){
                    Count_Map.put(url,1);
                }
                else{
                    Count_Map.put(url, Count_Map.get(url)+1);
                }
            }
            sub_reader.close();

            //after reading, sort by count
            List<Map.Entry<String,Integer>> list = new ArrayList<>(Count_Map.entrySet());

            //add the last first 100 entry
            for(int i=0; i<the_first_hundred.size(); i++){
                list.add(the_first_hundred.get(i));
            }

            //sort the list by entry's value, Dec
            Collections.sort(list, new Comparator<Map.Entry<String, Integer>>() {
                @Override
                public int compare(Map.Entry<String, Integer> o1, Map.Entry<String, Integer> o2) {
                    return o2.getValue().compareTo(o1.getValue());
                }
            });

            //store the first 100 result, need to clear the list first
            the_first_hundred.clear();
            for(int i=0; i<list.size()&&i<100; i++){
                the_first_hundred.add(list.get(i));
            }
        }

        //after the sub file loop, output the result
        File res_file = new File("test_file/test_res.txt");
        BufferedWriter res_writer = new BufferedWriter(new FileWriter(res_file));
        for(int i=0; i<the_first_hundred.size(); i++){
            //System.out.println("url = " + the_first_hundred.get(i).getKey()+", " +
            //        "count = " + the_first_hundred.get(i).getValue());
            res_writer.write("url = " + the_first_hundred.get(i).getKey()+", " +
                            "count = " + the_first_hundred.get(i).getValue());
            res_writer.newLine();
        }
        res_writer.flush();
        res_writer.close();
        Count_Map.clear();
    }

    public static void clearSubFile(){
        if(File_Map==null||File_Map.size()==0){
            return;
        }
        for(String key:File_Map.keySet()){
            File_Map.get(key).delete();
        }
        File_Map.clear();
    }
}




















