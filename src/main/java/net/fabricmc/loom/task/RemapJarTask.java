/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016-2021 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.task;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.Manifest;

import com.google.common.base.Preconditions;
import dev.architectury.tinyremapper.IMappingProvider;
import dev.architectury.tinyremapper.TinyRemapper;
import dev.architectury.tinyremapper.TinyUtils;
import dev.architectury.tinyremapper.extension.mixin.MixinExtension;
import org.cadixdev.at.AccessTransformSet;
import org.cadixdev.at.io.AccessTransformFormats;
import org.cadixdev.lorenz.MappingSet;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.tasks.Jar;
import org.jetbrains.annotations.ApiStatus;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.build.JarRemapper;
import net.fabricmc.loom.build.MixinRefmapHelper;
import net.fabricmc.loom.build.nesting.EmptyNestedJarProvider;
import net.fabricmc.loom.build.nesting.JarNester;
import net.fabricmc.loom.build.nesting.MergedNestedJarProvider;
import net.fabricmc.loom.build.nesting.NestedDependencyProvider;
import net.fabricmc.loom.build.nesting.NestedJarPathProvider;
import net.fabricmc.loom.build.nesting.NestedJarProvider;
import net.fabricmc.loom.configuration.JarManifestConfiguration;
import net.fabricmc.loom.configuration.accesswidener.AccessWidenerFile;
import net.fabricmc.loom.configuration.accesswidener.AccessWidenerJarProcessor;
import net.fabricmc.loom.configuration.providers.mappings.MappingsProviderImpl;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.LfWriter;
import net.fabricmc.loom.util.SourceRemapper;
import net.fabricmc.loom.util.TinyRemapperHelper;
import net.fabricmc.loom.util.ZipReprocessorUtil;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.loom.util.aw2at.Aw2At;
import net.fabricmc.lorenztiny.TinyMappingsReader;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.stitch.util.Pair;

public class RemapJarTask extends Jar {
	private static final String MANIFEST_PATH = "META-INF/MANIFEST.MF";

	private final RegularFileProperty input;
	private final Property<Boolean> addNestedDependencies;
	private final Property<Boolean> addDefaultNestedDependencies;
	private final Property<Boolean> remapAccessWidener;
	private final List<Action<TinyRemapper.Builder>> remapOptions = new ArrayList<>();
	private final Property<String> fromM;
	private final Property<String> toM;
	private final SetProperty<String> atAccessWideners;
	public JarRemapper jarRemapper;
	private FileCollection classpath;
	private final Set<Object> nestedPaths = new LinkedHashSet<>();

	public RemapJarTask() {
		super();
		LoomGradleExtension extension = LoomGradleExtension.get(getProject());
		input = getProject().getObjects().fileProperty();
		addNestedDependencies = getProject().getObjects().property(Boolean.class)
				.convention(false);
		addDefaultNestedDependencies = getProject().getObjects().property(Boolean.class)
				.convention(true);
		remapAccessWidener = getProject().getObjects().property(Boolean.class)
				.convention(false);
		fromM = getProject().getObjects().property(String.class)
				.convention("named");
		toM = getProject().getObjects().property(String.class)
				.convention(SourceRemapper.intermediary(getProject()));
		atAccessWideners = getProject().getObjects().setProperty(String.class)
				.empty();

		if (!extension.getMixin().getUseLegacyMixinAp().get()) {
			remapOptions.add(b -> b.extension(new MixinExtension()));
		}
	}

	@TaskAction
	public void doTask() throws Throwable {
		boolean singleRemap = false;

		if (jarRemapper == null) {
			singleRemap = true;
			jarRemapper = new JarRemapper();
		}

		scheduleRemap(singleRemap || LoomGradleExtension.get(getProject()).isRootProject());

		if (singleRemap) {
			jarRemapper.remap(getProject());
		}

		convertAwToAt();
	}

	public void scheduleRemap(boolean isMainRemapTask) throws Throwable {
		Project project = getProject();
		LoomGradleExtension extension = LoomGradleExtension.get(getProject());
		Path input = this.getInput().getAsFile().get().toPath();
		Path output = this.getArchivePath().toPath();

		if (!Files.exists(input)) {
			throw new FileNotFoundException(input.toString());
		}

		MappingsProviderImpl mappingsProvider = extension.getMappingsProvider();

		String fromM = this.fromM.get();
		String toM = this.toM.get();

		if (isMainRemapTask) {
			jarRemapper.addToClasspath(getRemapClasspath());

			jarRemapper.addMappings(TinyRemapperHelper.create((fromM.equals("srg") || toM.equals("srg")) && extension.shouldGenerateSrgTiny() ? mappingsProvider.getMappingsWithSrg() : mappingsProvider.getMappings(), fromM, toM, false));
		}

		for (File mixinMapFile : extension.getAllMixinMappings()) {
			if (mixinMapFile.exists()) {
				IMappingProvider provider = TinyUtils.createTinyMappingProvider(mixinMapFile.toPath(), fromM, "intermediary");
				jarRemapper.addMappings(!toM.equals("intermediary") ? remapToSrg(extension, provider, "intermediary", toM) : provider);
			}
		}

		// Add remap options to the jar remapper
		jarRemapper.addOptions(this.remapOptions);

		project.getLogger().info(":scheduling remap " + input.getFileName() + " from " + fromM + " to " + toM);

		NestedJarProvider nestedJarProvider = getNestedJarProvider();
		nestedJarProvider.prepare(getProject());

		jarRemapper.scheduleRemap(input, output)
				.supplyAccessWidener((remapData, remapper) -> {
					if (getRemapAccessWidener().getOrElse(false) && extension.getAccessWidenerPath().isPresent()) {
						AccessWidenerJarProcessor accessWidenerJarProcessor = extension.getJarProcessorManager().getByType(AccessWidenerJarProcessor.class);
						byte[] data;

						try {
							data = accessWidenerJarProcessor.getRemappedAccessWidener(remapper, toM);
						} catch (IOException e) {
							throw new RuntimeException("Failed to remap access widener", e);
						}

						AccessWidenerFile awFile = AccessWidenerFile.fromModJar(remapData.input);
						Preconditions.checkNotNull(awFile, "Failed to find accessWidener in fabric.mod.json / architectury.common.json: " + remapData.input);

						return Pair.of(awFile.name(), data);
					}

					return null;
				})
				.complete((data, accessWidener) -> {
					if (!Files.exists(output)) {
						throw new RuntimeException("Failed to remap " + input + " to " + output + " - file missing!");
					}

					if (extension.getMixin().getUseLegacyMixinAp().get()) {
						if (MixinRefmapHelper.addRefmapName(project, output)) {
							project.getLogger().debug("Transformed mixin reference maps in output JAR!");
						}
					} else if (extension.isForge()) {
						throw new RuntimeException("Forge must have useLegacyMixinAp enabled");
					}

					if (getAddNestedDependencies().getOrElse(false)) {
						JarNester.nestJars(nestedJarProvider.provide(), output.toFile(), project.getLogger());
					}

					if (accessWidener != null) {
						try {
							ZipUtils.replace(data.output, accessWidener.getLeft(), accessWidener.getRight());
						} catch (IOException e) {
							throw new UncheckedIOException("Failed to replace access widener in output jar", e);
						}
					}

					if (!extension.isForge()) {
						// Add data to the manifest
						try {
							int count = ZipUtils.transform(data.output, Map.of(MANIFEST_PATH, bytes -> {
								var manifest = new Manifest(new ByteArrayInputStream(bytes));
								var manifestConfiguration = new JarManifestConfiguration(project);

								manifestConfiguration.configure(manifest);
								manifest.getMainAttributes().putValue("Fabric-Mapping-Namespace", toM);

								ByteArrayOutputStream out = new ByteArrayOutputStream();
								manifest.write(out);
								return out.toByteArray();
							}));

							Preconditions.checkState(count > 0, "Did not transform any jar manifest");
						} catch (IOException e) {
							throw new UncheckedIOException("Failed to transform jar manifest", e);
						}
					}

					if (isReproducibleFileOrder() || !isPreserveFileTimestamps()) {
						try {
							ZipReprocessorUtil.reprocessZip(output.toFile(), isReproducibleFileOrder(), isPreserveFileTimestamps());
						} catch (IOException e) {
							throw new RuntimeException("Failed to re-process jar", e);
						}
					}
				});
	}

	private NestedJarProvider getNestedJarProvider() {
		if (!LoomGradleExtension.get(getProject()).supportsInclude()) {
			return EmptyNestedJarProvider.INSTANCE;
		}

		Configuration includeConfiguration = getProject().getConfigurations().getByName(Constants.Configurations.INCLUDE);

		if (!addDefaultNestedDependencies.getOrElse(true)) {
			return new NestedJarPathProvider(nestedPaths);
		}

		NestedJarProvider baseProvider = NestedDependencyProvider.createNestedDependencyProviderFromConfiguration(getProject(), includeConfiguration);

		if (nestedPaths.isEmpty()) {
			return baseProvider;
		}

		return new MergedNestedJarProvider(
				baseProvider,
				new NestedJarPathProvider(nestedPaths)
		);
	}

	private IMappingProvider remapToSrg(LoomGradleExtension extension, IMappingProvider parent, String from, String to) throws IOException {
		MappingTree mappings = (from.equals("srg") || to.equals("srg")) && extension.shouldGenerateSrgTiny() ? extension.getMappingsProvider().getMappingsWithSrg() : extension.getMappingsProvider().getMappings();

		return sink -> {
			parent.load(new IMappingProvider.MappingAcceptor() {
				@Override
				public void acceptClass(String srcName, String dstName) {
					String srgName = mappings.getClasses()
							.stream()
							.filter(it -> Objects.equals(it.getName(from), dstName))
							.findFirst()
							.map(it -> it.getName(to))
							.orElse(dstName);
					sink.acceptClass(srcName, srgName);
				}

				@Override
				public void acceptMethod(IMappingProvider.Member method, String dstName) {
					String srgName = mappings.getClasses()
							.stream()
							.flatMap(it -> it.getMethods().stream())
							.filter(it -> Objects.equals(it.getName(from), dstName))
							.findFirst()
							.map(it -> it.getName(to))
							.orElse(dstName);
					sink.acceptMethod(method, srgName);
				}

				@Override
				public void acceptField(IMappingProvider.Member field, String dstName) {
					String srgName = mappings.getClasses()
							.stream()
							.flatMap(it -> it.getFields().stream())
							.filter(it -> Objects.equals(it.getName(from), dstName))
							.findFirst()
							.map(it -> it.getName(to))
							.orElse(dstName);
					sink.acceptField(field, srgName);
				}

				@Override
				public void acceptMethodArg(IMappingProvider.Member method, int lvIndex, String dstName) {
				}

				@Override
				public void acceptMethodVar(IMappingProvider.Member method, int lvIndex, int startOpIdx, int asmIndex, String dstName) {
				}
			});
		};
	}

	private Path[] getRemapClasspath() {
		FileCollection files = this.classpath;

		if (files == null) {
			files = getProject().getConfigurations().getByName(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME);
		}

		return files.getFiles().stream()
				.map(File::toPath)
				.filter(Files::exists)
				.toArray(Path[]::new);
	}

	private void convertAwToAt() throws IOException {
		if (!this.atAccessWideners.isPresent()) {
			return;
		}

		Set<String> atAccessWideners = this.atAccessWideners.get();

		if (atAccessWideners.isEmpty()) {
			return;
		}

		AccessTransformSet at = AccessTransformSet.create();
		File jar = getArchiveFile().get().getAsFile();

		try (FileSystemUtil.Delegate fileSystem = FileSystemUtil.getJarFileSystem(jar, false)) {
			FileSystem fs = fileSystem.get();
			Path atPath = fs.getPath(Constants.Forge.ACCESS_TRANSFORMER_PATH);

			if (Files.exists(atPath)) {
				throw new FileAlreadyExistsException("Jar " + jar + " already contains an access transformer - cannot convert AWs!");
			}

			for (String aw : atAccessWideners) {
				Path awPath = fs.getPath(aw);

				if (Files.notExists(awPath)) {
					throw new NoSuchFileException("Could not find AW '" + aw + "' to convert into AT!");
				}

				try (BufferedReader reader = Files.newBufferedReader(awPath, StandardCharsets.UTF_8)) {
					at.merge(Aw2At.toAccessTransformSet(reader));
				}

				Files.delete(awPath);
			}

			LoomGradleExtension extension = LoomGradleExtension.get(getProject());
			MappingTree mappings = (fromM.get().equals("srg") || toM.get().equals("srg")) && extension.shouldGenerateSrgTiny() ? extension.getMappingsProvider().getMappingsWithSrg() : extension.getMappingsProvider().getMappings();

			try (TinyMappingsReader reader = new TinyMappingsReader(mappings, fromM.get(), toM.get())) {
				MappingSet mappingSet = reader.read();
				at = at.remap(mappingSet);
			}

			try (Writer writer = new LfWriter(Files.newBufferedWriter(atPath))) {
				AccessTransformFormats.FML.write(writer, at);
			}
		}
	}

	@InputFile
	public RegularFileProperty getInput() {
		return input;
	}

	@Input
	public Property<Boolean> getAddNestedDependencies() {
		return addNestedDependencies;
	}

	@Input
	public Property<Boolean> getAddDefaultNestedDependencies() {
		return addDefaultNestedDependencies;
	}

	@Input
	public Property<Boolean> getRemapAccessWidener() {
		return remapAccessWidener;
	}

	/**
	 * Gets the jar paths to the access wideners that will be converted to ATs for Forge runtime.
	 * If you specify multiple files, they will be merged into one.
	 *
	 * <p>The specified files will be converted and removed from the final jar.
	 *
	 * @return the property containing access widener paths in the final jar
	 */
	@Input
	public SetProperty<String> getAtAccessWideners() {
		return atAccessWideners;
	}

	public void remapOptions(Action<TinyRemapper.Builder> action) {
		this.remapOptions.add(action);
	}

	public RemapJarTask classpath(FileCollection collection) {
		if (this.classpath == null) {
			this.classpath = collection;
		} else {
			this.classpath = this.classpath.plus(collection);
		}

		return this;
	}

	@ApiStatus.Experimental
	// This only allows mod jars, proceed with care when trying to pass in configurations with projects, or something that depends on a task.
	public RemapJarTask include(Object... paths) {
		Collections.addAll(nestedPaths, paths);
		this.addNestedDependencies.set(true);

		return this;
	}

	@Input
	public Property<String> getFromM() {
		return fromM;
	}

	@Input
	public Property<String> getToM() {
		return toM;
	}
}
