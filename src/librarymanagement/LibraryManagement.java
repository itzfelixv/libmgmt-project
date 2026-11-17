/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Main.java to edit this template
 */
package librarymanagement;

/**
 *
 * @author Felix
 */
public class LibraryManagement {

    /** Whoever is logged in. Every window reads it from here. */
    public static final Session SESSION = new Session();

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        LoginFrame.main(args);
    }
    
}
