package de.fimatas.feeds;

import java.util.Random;

public class RandomStringGenerator {
    public static void main(String[] args) {
        String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!/()*,.-";
        int length = 70;

        StringBuilder randomString = new StringBuilder();
        Random random = new Random();

        for (int i = 0; i < length; i++) {
            int index = random.nextInt(characters.length());
            randomString.append(characters.charAt(index));
        }

        System.out.println("Zufällige Zeichenfolge: " + randomString);
    }
}