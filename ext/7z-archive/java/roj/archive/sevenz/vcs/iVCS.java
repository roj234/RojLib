package roj.archive.sevenz.vcs;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Range;
import roj.annotation.MayMutate;
import roj.archive.sevenz.SevenZEntry;
import roj.archive.sevenz.SevenZFile;
import roj.archive.sevenz.SevenZPacker;
import roj.archive.sevenz.util.SevenZArchiver;
import roj.collect.*;
import roj.crypt.Base64;
import roj.crypt.CryptoFactory;
import roj.io.IOUtil;
import roj.io.XDataInput;
import roj.io.XDataInputStream;
import roj.io.source.CompositeSource;
import roj.io.source.FileSource;
import roj.text.TextUtil;
import roj.util.ByteList;
import roj.util.DynByteBuf;
import roj.util.Helpers;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.ObjIntConsumer;

/**
 * Incremental solid Version Control System.
 * 需要解决的问题：
 * 1. 分布式合并？字块复制和冲突检查。
 * 2. 不在启动时加载所有的Commit？修改当前结构，每个分卷都是独立的7z压缩文件，它们又和COMMIT一起套在外层的大7z中，这样可以预防元数据损坏
 * 3. 基于hash搞内容寻址存储
 * 4. 基于XTS的加密支持
 * @author Roj234
 * @since 2025/12/18 00:51
 */
public class iVCS {
	public static final String HASH_ALGORITHM = "SHA-256";
	public static final int HASH_BYTES = 32;

	public static final String IVCS_METADATA = "META";

	private transient SevenZFile archive;
	private transient IntMap<SevenZFile> archives;
	private transient IntFunction<SevenZFile> externalFileLoader;

	public int vcsVersion;

	public String activateBranch;

	private static final XashMap.Template<String, Branch> BRANCH_TEMPLATE = XashMap.forType(String.class, Branch.class).key("name").build();
	private static final XashMap.Template<byte[], Commit> COMMIT_TEMPLATE = XashMap.forType(byte[].class, Commit.class).key("hash").hasher(Hasher.array(byte[].class)).build();

	public XashMap<String, Branch> branches = BRANCH_TEMPLATE.create();
	public XashMap<byte[], Commit> commits = COMMIT_TEMPLATE.create();

	public static class Branch {
		public String name;
		public byte[] commitHash;

		private transient Branch _next;
	}

	public static class Commit {
		static final int FLAG_HAS_SNAPSHOT = 1, FLAG_HAS_CRYPTO_HASH = 2, FLAG_HAS_DIFF_ALGORITHMS = 4;

		transient SevenZEntry _ref;

		transient int flags;
		transient List<SevenZEntry> files;
		transient ToIntMap<SevenZEntry> fileLookup;
		transient volatile byte[] diffAlgorithms;
		transient byte[] fileHashes;
		transient Int2IntMap externalIds;

		int versionId;
		byte[] hash;

		public long date;
		public String desc;
		public byte[][] upstreamCommitHashes;

		public boolean isCommited() {return _ref != null || versionId > 0;}
		public long getVersionId() {return versionId;}
		public int getFlags() {return flags;}
		public byte[] getHash() {return hash;}

		public List<SevenZEntry> getFiles() {return files;}
		public  byte[] getFileHashes() {return fileHashes;}
		public @Nullable byte[] getDiffAlgorithms() {return diffAlgorithms;}

		private transient Commit _next;

		@Override
		public String toString() {
			String hexHash = hash == null ? "<no hash>" : TextUtil.bytes2hex(hash);

			String haxParentHash;
			if (upstreamCommitHashes == null) {
				haxParentHash = "<no parent>";
			} else {
				int iMax = upstreamCommitHashes.length - 1;
				if (iMax == -1) {
					haxParentHash = "<no parent>";
				} else {
					StringBuilder b = new StringBuilder();
					b.append('[');
					for (int i = 0; ; i++) {
						b.append(TextUtil.bytes2hex(upstreamCommitHashes[i]));
						if (i == iMax) {
							haxParentHash = b.append(']').toString();
							break;
						}
						b.append(", ");
					}
				}
			}

			return "Commit{"+hexHash+"/"+versionId +
					", flags=" + flags +
					", date=" + date +
					", desc='" + desc + '\'' +
					", upstream=" + haxParentHash +
					'}';
		}
	}

	public iVCS(SevenZFile archive) throws IOException {
		this.archive = archive;

		SevenZEntry[] entries = archive.getEntriesByPresentOrder();
		if (entries.length > 0) {
			SevenZEntry lastEntry = entries[entries.length - 1];
			if (lastEntry.getName().startsWith(IVCS_METADATA)) {
				int i = Integer.parseInt(lastEntry.getName().substring(IVCS_METADATA.length() + 1), 36);
				if (i > 0 && i < entries.length) lastEntry = entries[i];

				if (lastEntry.getName().equals(IVCS_METADATA)) {
					try (var in = new XDataInputStream(archive.getInputStream(lastEntry))) {
						initialize(in);
					}

					String prefix = "COMMIT/";
					for (SevenZEntry entry : entries) {
						if (entry.getName().startsWith(prefix)) {
							var commit = new Commit();
							commit._ref = entry;

							List<String> tokens = TextUtil.split(entry.getName(), "/");
							commit.hash = Base64.decode(tokens.get(1), IOUtil.getSharedByteBuf(), Base64.B64_URL_SAFE_REV).toByteArray();

							commits.add(commit);
						}
					}

					return;
				}
			}

			throw new IOException("Illegal VCS metadata");
		}

		reset();
	}

	private void reset() {
		this.vcsVersion = 0;
		this.activateBranch = "master";

		Branch mainBranch = new Branch();
		mainBranch.name = "master";
		this.branches.add(mainBranch);
	}

	private void initialize(XDataInput in) throws IOException {
		int dataLength = in.readInt();
		if (dataLength != 4)
			throw new IOException("Only support version 1 metadata");

		vcsVersion = in.readInt();
		activateBranch = in.readVUIUTF();
		int size = in.readVUInt();
		for (int i = 0; i < size; i++) {
			var branch = new Branch();
			branch.name = in.readVUIUTF();
			if (in.readBoolean()) {
				branch.commitHash = new byte[HASH_BYTES];
				in.readFully(branch.commitHash);
			}

			branches.add(branch);
		}
	}

	private void initializeCommit(Commit commit) {
		try (var in = new XDataInputStream(archive.getInputStream(commit._ref, null))) {
			int count = in.readUnsignedByte();
			var upstreamCommitHashes = new byte[count][HASH_BYTES];
			for (int i = 0; i < count; i++) in.readFully(upstreamCommitHashes[i]);

			commit.upstreamCommitHashes = upstreamCommitHashes;
			commit.versionId = in.readVUInt();
			commit.date = in.readLong();
			commit.desc = in.readUTF();

			// TLV
			while (true) {
				int tag = in.read();
				if (tag < 0) break;

				int length = in.readVUInt();
				switch (tag) {
					case 1 -> {
						commit.flags |= Commit.FLAG_HAS_SNAPSHOT;

						int refCount = in.readVUInt();
						List<SevenZEntry> entries = Arrays.asList(new SevenZEntry[refCount]);
						for (int i = 0; i < refCount; i++) {
							long fileId = in.readLong();
							var archive = this.archive;
							if ((fileId & 0xFFFFFFFF00000000L) != 0) {
								// External ref
								int archiveId = (int) (fileId >>> 32);

								if (commit.externalIds == null) commit.externalIds = new Int2IntMap();
								commit.externalIds.put(i, archiveId);

								if (archives == null) archives = new IntMap<>();
								archive = archives.computeIfAbsentI(archiveId, externalFileLoader);
							}
							entries.set(i, archive.getEntriesByPresentOrder()[(int)fileId]);
						}
						commit.files = entries;
					}
					case 2 -> {
						commit.flags |= Commit.FLAG_HAS_CRYPTO_HASH;

						byte[] hash = new byte[length];
						in.readFully(hash);

						commit.fileHashes = hash;
					}
					case 3 -> {
						byte[] algIds = new byte[length];
						in.readFully(algIds);

						commit.diffAlgorithms = algIds;
					}
					default -> in.skipForce(length);
				}
			}
			commit._ref = null;
		} catch (IOException e) {
			Helpers.athrow(e);
		}
	}

	public void newBranch(String branchName) {
		Branch branch = new Branch();
		branch.name = branchName;
		branches.put(branchName, branch);
	}

	public void removeBranch(String branchName) {
		branches.removeKey(branchName);
	}

	@Nullable
	public Branch getActiveBranch() {
		return branches.get(activateBranch);
	}

	public Branch getBranch(String branchName) {
		return branches.get(branchName);
	}

	@Nullable
	public Commit getActiveCommit() {
		Branch branch = branches.get(activateBranch);
		return branch == null ? null : getCommit(branch.commitHash);
	}

	public Commit getCommit(byte[] commitHash) {
		Commit commit = commits.get(commitHash);
		if (commit != null && commit._ref != null) {
			initializeCommit(commit);
		}
		return commit;
	}

	public static class FileView implements ObjIntConsumer<SevenZEntry> {
		private final Map<String, SevenZEntry> files = new HashMap<>();

		public Map<String, SevenZEntry> getFiles() {return files;}

		@Override
		public void accept(SevenZEntry entry, int prefixLength) {
			String name = entry.getName().substring(prefixLength);
			if (entry.isAntiItem()) {
				files.remove(name);
			} else {
				files.put(name, entry);
			}
		}
	}

	public void buildFileTree(ObjIntConsumer<SevenZEntry> versions) {
		byte[] latestCommitHash = branches.get(activateBranch).commitHash;
		if (latestCommitHash != null)
			buildFileTree(latestCommitHash, versions);
	}

	public void buildFileTree(byte[] commitHash, ObjIntConsumer<SevenZEntry> versions) {
		Commit commit = getCommit(commitHash);
		if (commit == null) throw new IllegalArgumentException("Commit " + TextUtil.bytes2hex(commitHash) + " not found");

		buildFileTree(commit, versions);
	}

	public void buildFileTree(Commit commit, ObjIntConsumer<SevenZEntry> versions) {
		if (!commit.isCommited()) throw new IllegalStateException("Commit " + commit + " is not committed");

		List<SevenZEntry> entries = commit.files;

		if ((commit.flags & Commit.FLAG_HAS_SNAPSHOT) == 0) {
			for (byte[] upstreamCommitHash : commit.upstreamCommitHashes) {
				buildFileTree(upstreamCommitHash, versions);
			}

			if (entries == null) {
				entries = new ArrayList<>();
				commit.files = entries;

				// TODO optimize （这是密集的，理论上可以存wordblock索引）
				String prefix = Integer.toString(commit.versionId, 36) + "/";
				for (SevenZEntry entry : archive.getEntriesByPresentOrder()) {
					if (entry.getName().startsWith(prefix)) {
						entries.add(entry);
					}
				}

				if (!entries.isEmpty()) {
					if ((commit.flags & Commit.FLAG_HAS_CRYPTO_HASH) == 0) {
						throw new IllegalStateException("Hash is required for contentful commits");
					}
				}
			}
		} else {
			assert entries != null;
		}

		int prefixLength = Integer.toString(commit.versionId, 36).length() + 1;
		for (SevenZEntry entry : entries) {
			// TODO supply archiveId ?
			versions.accept(entry, prefixLength);
		}
	}

	private transient Path metadataBackup;

	public SevenZPacker beginWrite(SevenZFile archive) throws IOException {
		var source = ((CompositeSource) archive.getSource());

		SevenZPacker out = archive.append();

		boolean isNew = out.getEmptyFiles().isEmpty();
		if (!isNew) {
			FileSource metadataChunk = (FileSource) source.getCurrentFragment();
			metadataChunk.close();

			// 备份元数据
			Path path = metadataChunk.getFile().toPath();
			Path path2 = Path.of(metadataChunk.getFile().getAbsolutePath()+".bak");
			Files.move(path, metadataBackup = path2, StandardCopyOption.ATOMIC_MOVE);

			out.removeLastWordBlock();
			out.removeEmptyFile(out.getEmptyFiles().size()-1);
		}

		source.setLength(source.position());
		source.next();

		return out;
	}

	/**
	 *
	 * @param commit       提交
	 * @param changed      发生变化的文件
	 * @see SevenZArchiver
	 *
	 */
	public void preCommit(Commit commit, @MayMutate List<SevenZEntry> changed, boolean createSnapshot) {
		if (commit.isCommited()) throw new IllegalStateException("Already commited");

		int versionId;
		synchronized (this) {
			versionId = ++vcsVersion;
			commits.add(commit);
		}

		commit.versionId = versionId;
		String prefix = versionId+"/";

		changed.sort((o1, o2) -> {
			int cmp = Long.compare(o2.getSize(), o1.getSize());
			if (cmp != 0) return cmp;

			return o1.getName().compareTo(o2.getName());
		});
		for (SevenZEntry entry : changed) entry.setName(prefix.concat(entry.getName()));

		if (createSnapshot) {
			// TODO
			var view = new FileView();
			buildFileTree(commit, view);

			commit.files = new ArrayList<>(view.getFiles().values());
			commit.fileHashes = new byte[commit.files.size() * HASH_BYTES];

			throw new IllegalArgumentException("Not implemented");
		} else {
			commit.files = changed;
			int contentfulFiles = 0;
			for (; contentfulFiles < changed.size(); contentfulFiles++) {
				if (changed.get(contentfulFiles).getSize() == 0) {
					break;
				}
			}

			commit.fileHashes = new byte[contentfulFiles * HASH_BYTES];
			// reverse lookup
			commit.fileLookup = new ToIntMap<>();
			commit.fileLookup.setHasher(Hasher.identity());
			for (int j = 0; j < contentfulFiles; j++) {
				commit.fileLookup.putInt(changed.get(j), j);
			}
		}
	}

	public static class CommitFileOptions {
		@Range(from = 0, to = 0xFF)
		public int diffAlgorithmId;
	}

	public OutputStream commitFile(Commit commit, SevenZEntry entry, OutputStream out, @Nullable CommitFileOptions attributes) {
		int fileIndex;
		synchronized (commit) {
			fileIndex = commit.fileLookup.removeInt(entry);
		}
		if (fileIndex < 0) throw new IllegalStateException("Unlisted entry "+entry);

		if (attributes != null) {
			if (attributes.diffAlgorithmId != 0) {
				if (commit.diffAlgorithms == null) {
					synchronized (commit) {
						if (commit.diffAlgorithms == null) {
							commit.diffAlgorithms = new byte[commit.files.size()];
						}
					}
				}
				commit.diffAlgorithms[fileIndex] = (byte) attributes.diffAlgorithmId;
			}
		}

		return new FilterOutputStream(out) {
			MessageDigest digest = CryptoFactory.getMessageDigest(HASH_ALGORITHM);

			public void write(int b) throws IOException {
				out.write(b);
				digest.update((byte)b);
			}

			public void write(byte[] b, int off, int len) throws IOException {
				out.write(b, off, len);
				digest.update(b, off, len);
			}

			@Override
			public void close() {
				if (digest != null) {
					try {
						digest.digest(commit.fileHashes, HASH_BYTES * fileIndex, HASH_BYTES);
					} catch (DigestException e) {
						Helpers.athrow(e);
					}
					digest = null;
				}
			}
		};
	}

	/**
	 * 将一个Commit提交到系统中.
	 * 这个函数只负责更新元数据.
	 * @param commit 提交
	 */
	public void postCommit(Commit commit) {
		MessageDigest sha1 = CryptoFactory.getMessageDigest(HASH_ALGORITHM);

		var buf = new ByteList();
		for (byte[] commitHash : commit.upstreamCommitHashes) {
			getCommit(commitHash);
			buf.put(commitHash);
		}
		buf.put(0);
		buf.putLong(commit.date);
		buf.putUTFData(commit.desc);
		buf.put(0);

		for (var entry : commit.fileLookup.selfEntrySet()) {
			if (entry.getKey().getSize() > 0)
				throw new IllegalStateException("Contentful entries "+entry.getKey()+" must be wrapped");
		}
		commit.fileLookup = null;

		for (SevenZEntry entry : commit.files) {
			if (buf.readableBytes() > 1024) {
				sha1.update(buf.list, 0, buf.readableBytes());
				buf.clear();
			}

			buf.putUTFData(entry.getName()).put(0);
		}

		sha1.update(buf.list, 0, buf.readableBytes());
		buf.release();

		sha1.update(commit.fileHashes);

		commit.hash = sha1.digest();
	}

	public void write(SevenZPacker writer, List<Commit> commits) throws IOException {
		CompositeSource source = (CompositeSource) writer.getSource();

		for (Commit commit : commits) {
			SevenZEntry entry = SevenZEntry.of("COMMIT/"+DynByteBuf.wrap(commit.hash).base64UrlSafe());
			entry.setModificationTime(commit.date);

			writer.beginEntry(entry);
			try (var out = DynByteBuf.toStream(writer, false)) {
				out.put(commit.upstreamCommitHashes.length);
				for (byte[] commitHash : commit.upstreamCommitHashes) {
					out.put(commitHash);
				}
				out.putVULong(commit.versionId).putLong(commit.date).putUTF(commit.desc);

				if (commit.fileHashes != null) {
					int len = commit.fileHashes.length;
					out.put(2).putVUInt(len).put(commit.fileHashes);
				}
				if (commit.diffAlgorithms != null) {
					int len = commit.diffAlgorithms.length;
					out.put(3).putVUInt(len).put(commit.diffAlgorithms);
				}
			}
		}

		writer.finishWordBlock();
		source.next(); // metadata chunk

		int fileIndex = writer.getFiles().size();
		SevenZEntry entry = SevenZEntry.of(IVCS_METADATA);
		entry.setModificationTime(System.currentTimeMillis());
		writer.beginEntry(entry);

		try (var out = DynByteBuf.toStream(writer)) {
			out.putInt(4)
					.putInt(vcsVersion)
					.putVUIUTF(activateBranch)
					.putVUInt(branches.size());
			for (Branch branch : branches) {
				boolean exist = branch.commitHash != null;
				out.putVUIUTF(branch.name).putBool(exist);
				if (exist) out.write(branch.commitHash);
			}
		}

		// index, as empty files are store last
		writer.beginEntry(SevenZEntry.ofNoAttribute(IVCS_METADATA+"."+Integer.toString(fileIndex, 36)));
		writer.finish();

		if (metadataBackup != null)
			Files.deleteIfExists(metadataBackup);
	}
}
