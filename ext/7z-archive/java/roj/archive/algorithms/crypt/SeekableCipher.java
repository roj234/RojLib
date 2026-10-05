package roj.archive.algorithms.crypt;

/**
 * @author Roj234
 * @since 2025/12/26 09:40
 */
public interface SeekableCipher {
	long getSectorSize();
	long getSector();
	void setSector(long sector);
}
