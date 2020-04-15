package framework;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Mathematics {
    public static <E> List<E> decodePermutationOfThree(int order, List<E> list) {
        // Obviously these are just permutations of a list of 3 items. Using some abstract algebra, you wouldn't need to hardcode this
        // But that is outside the scope of this course
        switch (order) {
            case 0:
                return new ArrayList<E>(){}; // -1 -1
            case 1:
                return new ArrayList<E>(Arrays.asList(list.get(0))); // 0 -1
            case 2:
                return new ArrayList<E>(Arrays.asList(list.get(1))); // 1 -1
            case 3:
                return new ArrayList<E>(Arrays.asList(list.get(2))); // 2 -1
            case 4:
                return new ArrayList<E>(Arrays.asList(list.get(0),list.get(1))); // 0 1
            case 5:
                return new ArrayList<E>(Arrays.asList(list.get(0),list.get(2))); // 0 2
            case 6:
                return new ArrayList<E>(Arrays.asList(list.get(1),list.get(0))); // 1 0
            case 7:
                return new ArrayList<E>(Arrays.asList(list.get(1),list.get(2))); // 1 2
            case 8:
                return new ArrayList<E>(Arrays.asList(list.get(2),list.get(0))); // 2 0
            case 9:
                return new ArrayList<E>(Arrays.asList(list.get(2),list.get(1))); // 2 1
        }
        return null;
    }

    public static int encodePermutationOfThree(int a, int b) {
        switch (a) {
            case 0:
                switch (b) {
                    case 1:
                        return 4;
                    case 2:
                        return 5;
                    case -1:
                        return 1;
                }
                break;
            case 1:
                switch (b) {
                    case 0:
                        return 6;
                    case 2:
                        return 7;
                    case -1:
                        return 2;
                }
                break;
            case 2:
                switch (b) {
                    case 0:
                        return 8;
                    case 1:
                        return 9;
                    case -1:
                        return 3;
                }
                break;
            case -1:
                return 0;
        }
        return -1;
    }

    public static <E> List<E> decodePermutationOfTwo(int order, List<E> list) {
        // See description of permutationOfThree
        switch (order) {
            case 0:
                return new ArrayList<E>(){}; // -1 -1
            case 1:
                return new ArrayList<E>(Arrays.asList(list.get(0))); // 0 -1
            case 2:
                return new ArrayList<E>(Arrays.asList(list.get(1))); // 1 -1
            case 3:
                return new ArrayList<E>(Arrays.asList(list.get(0),list.get(1))); // 0 1
            case 4:
                return new ArrayList<E>(Arrays.asList(list.get(1),list.get(0)));// 1 0
        }
        return null;
    }

    public static int encodePermutationOfTwo(int a, int b) {
        if (a==-1 && b==-1) {
            return 0;
        } else if (a==0 && b==-1) {
            return 1;
        } else if (a==1 && b==-1) {
            return 2;
        } else if (a==0 && b==1) {
            return 3;
        } else if (a==1 && b==0) {
            return 4;
        }
        return -1;
    }
}
