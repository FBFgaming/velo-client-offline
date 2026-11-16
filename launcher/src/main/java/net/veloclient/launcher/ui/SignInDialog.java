package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import net.veloclient.launcher.auth.AuthException;
import net.veloclient.launcher.auth.MicrosoftAuth;
import net.veloclient.launcher.auth.MinecraftSession;
import net.veloclient.launcher.auth.OfflineAuth;

import java.awt.Desktop;
import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Modal "device code" sign-in dialog, built as a guided 3-step flow: the code is shown big and
 * copied automatically FIRST, and the Microsoft page only opens when the user clicks "Open login
 * page" - opening it straight away (the old behaviour) meant many people never saw the code and
 * went looking for a 2FA code in their email/phone instead.
 *
 * <p>The same dialog also carries the offline option: a footer link swaps onto a username-only
 * panel ({@link OfflineAuth}) for people without a Microsoft account, so "sign in" always has a
 * way in. The Microsoft flow keeps polling in the background until the user switches (it is
 * cancelled then), so backing out of the offline panel just restarts it.
 */
public final class SignInDialog {

	private static final String LOGIN_URL_FALLBACK = "https://www.microsoft.com/link";

	private SignInDialog() {
	}

	/** Everything the flow updates once the real code arrives (or a demo code is filled in). */
	private static final class View {
		final Stage stage;
		final Label code = new Label("· · · · · · · ·");
		final Label copied = new Label();
		final Label status = new Label("Requesting a sign-in code...");
		final Button copy = new Button("Copy");
		final Button open = new Button("Open login page");
		final Button cancel = new Button("Cancel");
		/** The login address, always visible and selectable: on some PCs the browser never opens (and Windows still says it did). */
		final javafx.scene.control.TextField link = new javafx.scene.control.TextField(LOGIN_URL_FALLBACK);
		final Button copyLink = new Button("Copy link");
		final ProgressIndicator spinner = new ProgressIndicator();
		final Label[] steps = new Label[3];
		final Button offlineLink = new Button("No Microsoft account? Play with an offline username instead");
		/** The Microsoft-flow card, kept so the offline panel can swap back to it. */
		VBox box;
		String url = LOGIN_URL_FALLBACK;

		View(Stage stage) {
			this.stage = stage;
		}

		void setStep(int active) {
			for (int i = 0; i < steps.length; i++) {
				steps[i].getStyleClass().removeAll("signin-step-active", "signin-step-done");
				if (i < active) {
					steps[i].getStyleClass().add("signin-step-done");
				} else if (i == active) {
					steps[i].getStyleClass().add("signin-step-active");
				}
			}
		}

		void fill(String userCode, String verificationUri) {
			url = verificationUri == null || verificationUri.isBlank() ? LOGIN_URL_FALLBACK : verificationUri;
			code.setText(userCode);
			link.setText(url);
			copy.setDisable(false);
			open.setDisable(false);
			copyToClipboard(userCode);
			copied.setText("Copied to your clipboard");
			status.setText("Next: open the login page, paste the code, then sign in.");
			setStep(1);
			pop(code);
		}
	}

	public static void show(Stage owner, String clientId, Consumer<MinecraftSession> onSuccess, Consumer<String> onError) {
		open(owner, clientId, onSuccess, onError, false);
	}

	/** Opens straight onto the offline panel (the account menu's "Add offline account"). */
	public static void showOffline(Stage owner, String clientId, Consumer<MinecraftSession> onSuccess, Consumer<String> onError) {
		open(owner, clientId, onSuccess, onError, true);
	}

	private static void open(Stage owner, String clientId, Consumer<MinecraftSession> onSuccess, Consumer<String> onError,
			boolean startOffline) {
		View view = build(owner);

		var executor = Executors.newVirtualThreadPerTaskExecutor();
		// Held in a reference so the offline panel (and Back) can cancel or restart the polling.
		AtomicReference<java.util.concurrent.Future<?>> microsoftFlow = new AtomicReference<>();
		Runnable startMicrosoftFlow = () -> {
			view.status.setText("Requesting a sign-in code...");
			view.code.setText("· · · · · · · ·");
			view.copied.setText("");
			view.setStep(0);
			microsoftFlow.set(executor.submit(() -> {
				try {
					MicrosoftAuth auth = new MicrosoftAuth(clientId);
					MinecraftSession session = auth.signInWithDeviceCode(deviceCode -> Platform.runLater(() -> {
						view.fill(deviceCode.userCode(), deviceCode.verificationUri());
						view.copy.setOnAction(e -> {
							copyToClipboard(deviceCode.userCode());
							view.copied.setText("Copied again");
							pop(view.copied);
						});
					}));
					Platform.runLater(() -> {
						view.stage.close();
						// Whatever the page refresh after sign-in throws must not look like a failed sign-in crash:
						// the session is already valid at this point.
						try {
							onSuccess.accept(session);
						} catch (RuntimeException e) {
							net.veloclient.launcher.LauncherLog.warn("After sign-in", e);
						}
					});
				} catch (AuthException e) {
					if (Thread.currentThread().isInterrupted()) {
						return; // Cancelled by the user - no "Sign-in failed" popup.
					}
					Platform.runLater(() -> {
						view.stage.close();
						onError.accept(e.getMessage());
					});
				} catch (Throwable e) {
					if (Thread.currentThread().isInterrupted()) {
						return;
					}
					net.veloclient.launcher.LauncherLog.warn("Sign-in failed", e);
					Platform.runLater(() -> {
						view.stage.close();
						onError.accept("Unexpected error: " + e);
					});
				}
			}));
		};

		VBox offlinePanel = buildOfflinePanel(owner, session -> {
			java.util.concurrent.Future<?> ms = microsoftFlow.get();
			if (ms != null) {
				ms.cancel(true); // Silent - the flow's own interrupt checks swallow it.
			}
			view.stage.close();
			try {
				onSuccess.accept(session);
			} catch (RuntimeException e) {
				net.veloclient.launcher.LauncherLog.warn("After offline sign-in", e);
			}
		}, () -> {
			view.stage.getScene().setRoot(view.box);
			startMicrosoftFlow.run();
		});
		view.offlineLink.setOnAction(e -> {
			java.util.concurrent.Future<?> ms = microsoftFlow.get();
			if (ms != null) {
				ms.cancel(true);
			}
			view.stage.getScene().setRoot(offlinePanel);
		});

		view.cancel.setOnAction(e -> {
			java.util.concurrent.Future<?> ms = microsoftFlow.get();
			if (ms != null) {
				ms.cancel(true);
			}
			view.stage.close();
		});
		view.stage.setOnCloseRequest(e -> {
			java.util.concurrent.Future<?> ms = microsoftFlow.get();
			if (ms != null) {
				ms.cancel(true);
			}
		});
		view.stage.show();

		if (startOffline) {
			view.stage.getScene().setRoot(offlinePanel);
		} else {
			startMicrosoftFlow.run();
		}
	}

	/** Dev-only preview (launcher screenshot tour): the dialog with a sample code, no network. */
	public static void demo(Stage owner, Object ignoredTheme) {
		View view = build(owner);
		view.offlineLink.setDisable(true);
		view.cancel.setOnAction(e -> view.stage.close());
		view.stage.show();
		javafx.animation.PauseTransition later = new javafx.animation.PauseTransition(javafx.util.Duration.millis(500));
		later.setOnFinished(e -> view.fill("H7KQ-2XRM", LOGIN_URL_FALLBACK));
		later.play();
	}

	private static View build(Stage owner) {
		Stage stage = new Stage(StageStyle.TRANSPARENT);
		stage.initOwner(owner);
		stage.initModality(Modality.WINDOW_MODAL);
		View view = new View(stage);

		Label heading = new Label("Sign in with Microsoft");
		heading.getStyleClass().add("signin-heading");
		Label sub = new Label("Use the Microsoft account that owns Minecraft.");
		sub.getStyleClass().add("signin-sub");

		String[] stepNames = {"1  Copy code", "2  Open login page", "3  Sign in"};
		HBox stepRow = new HBox(6);
		stepRow.setAlignment(Pos.CENTER);
		for (int i = 0; i < 3; i++) {
			view.steps[i] = new Label(stepNames[i]);
			view.steps[i].getStyleClass().add("signin-step");
			stepRow.getChildren().add(view.steps[i]);
		}
		view.setStep(0);

		Label codeCaption = new Label("YOUR SIGN-IN CODE");
		codeCaption.getStyleClass().add("signin-caption");
		view.code.getStyleClass().add("signin-code");
		view.copy.getStyleClass().add("signin-copy");
		view.copy.setDisable(true);
		HBox codeRow = new HBox(12, view.code, view.copy);
		codeRow.setAlignment(Pos.CENTER);
		view.copied.getStyleClass().add("signin-copied");
		VBox codeCard = new VBox(8, codeCaption, codeRow, view.copied);
		codeCard.setAlignment(Pos.CENTER);
		codeCard.getStyleClass().add("signin-code-card");

		Label note = new Label("This code comes from this window. It is not a code from your email, phone or authenticator app.");
		note.setWrapText(true);
		note.getStyleClass().add("signin-note");

		view.open.getStyleClass().add("primary-button");
		view.open.setDefaultButton(true);
		view.open.setDisable(true);
		view.open.setMaxWidth(Double.MAX_VALUE);
		view.open.setOnAction(e -> {
			String url = view.url;
			// Off the FX thread: AWT's Desktop on the JavaFX thread can hang or crash on some Windows setups.
			Thread.ofVirtual().start(() -> {
				if (!openInBrowser(url)) {
					Platform.runLater(() -> {
						view.status.setText("Couldn't open a browser - copy the link below and open it yourself.");
						view.link.requestFocus();
						view.link.selectAll();
					});
				}
			});
			view.setStep(2);
			view.status.setText("Paste the code on the Microsoft page and sign in - this window finishes by itself.");
		});
		view.cancel.getStyleClass().add("ghost-button");
		HBox.setHgrow(view.open, javafx.scene.layout.Priority.ALWAYS);
		HBox actions = new HBox(10, view.cancel, view.open);
		actions.setAlignment(Pos.CENTER);

		// Browser didn't open? The address to paste into any browser yourself (or open on your phone).
		Label linkCaption = new Label("Browser didn't open? Paste this link into any browser - also works on your phone:");
		linkCaption.setWrapText(true);
		linkCaption.getStyleClass().add("signin-note");
		view.link.setEditable(false);
		view.link.setFocusTraversable(false);
		view.link.setOnMouseClicked(e -> view.link.selectAll());
		HBox.setHgrow(view.link, javafx.scene.layout.Priority.ALWAYS);
		view.copyLink.getStyleClass().add("signin-copy");
		view.copyLink.setOnAction(e -> {
			copyToClipboard(view.link.getText());
			view.copied.setText("Link copied - paste it in your browser, then copy the code again");
			view.copy.setText("Copy code");
			pop(view.copied);
		});
		HBox linkRow = new HBox(8, view.link, view.copyLink);
		linkRow.setAlignment(Pos.CENTER_LEFT);
		VBox linkBox = new VBox(6, linkCaption, linkRow);

		view.spinner.setPrefSize(16, 16);
		view.spinner.setMaxSize(16, 16);
		view.status.getStyleClass().add("signin-status");
		view.status.setWrapText(true);
		HBox statusRow = new HBox(8, view.spinner, view.status);
		statusRow.setAlignment(Pos.CENTER_LEFT);

		VBox box = new VBox(16, heading, sub, stepRow, codeCard, note, actions, linkBox, statusRow);
		box.setAlignment(Pos.TOP_CENTER);
		box.setPadding(new Insets(28));
		box.getStyleClass().add("signin-dialog");
		box.setPrefWidth(440);
		VBox.setMargin(sub, new Insets(-12, 0, 0, 0));
		// Same theme colors as the main window (its root carries them as inline lookups).
		box.setStyle(owner.getScene().getRoot().getStyle());

		view.offlineLink.getStyleClass().add("signin-offline-link");
		box.getChildren().add(view.offlineLink);
		view.box = box;

		Scene scene = new Scene(box);
		scene.setFill(Color.TRANSPARENT);
		scene.getStylesheets().addAll(owner.getScene().getStylesheets());
		stage.setScene(scene);
		// A raw Stage (needed for StageStyle.TRANSPARENT) doesn't get owner-centering on its own.
		stage.setOnShown(e -> {
			stage.setX(owner.getX() + (owner.getWidth() - stage.getWidth()) / 2);
			stage.setY(owner.getY() + (owner.getHeight() - stage.getHeight()) / 2);
			box.setOpacity(0);
			box.setScaleX(0.96);
			box.setScaleY(0.96);
			javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(javafx.util.Duration.millis(180), box);
			fade.setToValue(1);
			javafx.animation.ScaleTransition grow = new javafx.animation.ScaleTransition(javafx.util.Duration.millis(220), box);
			grow.setToX(1);
			grow.setToY(1);
			grow.setInterpolator(javafx.animation.Interpolator.SPLINE(0.2, 0.9, 0.3, 1));
			new javafx.animation.ParallelTransition(fade, grow).play();
		});
		return view;
	}

	/**
	 * The offline panel: a username is enough - the session's UUID is Minecraft's own offline-mode
	 * derivation ({@code UUID.nameUUIDFromBytes("OfflinePlayer:" + name)}), the same value an
	 * offline-mode vanilla server assigns. Same card look as the Microsoft flow.
	 */
	private static VBox buildOfflinePanel(Stage owner, Consumer<MinecraftSession> onContinue, Runnable backToMicrosoft) {
		VBox panel = new VBox(16);
		panel.setAlignment(Pos.TOP_CENTER);
		panel.setPadding(new Insets(28));
		panel.getStyleClass().add("signin-dialog");
		panel.setPrefWidth(440);
		panel.setStyle(owner.getScene().getRoot().getStyle());

		Label heading = new Label("Play offline");
		heading.getStyleClass().add("signin-heading");
		Label sub = new Label("A username-only account, exactly how Minecraft's own offline mode works.");
		sub.getStyleClass().add("signin-sub");
		sub.setWrapText(true);
		VBox.setMargin(sub, new Insets(-12, 0, 0, 0));

		Label caption = new Label("USERNAME");
		caption.getStyleClass().add("signin-caption");
		TextField name = new TextField();
		name.setPromptText("3-16 characters - like your in-game name");
		// Minecraft names cap at 16 characters; typing more would only fail validation later.
		name.setTextFormatter(new javafx.scene.control.TextFormatter<String>(change ->
				change.getControlNewText().length() <= 16 ? change : null));
		name.getStyleClass().add("signin-name-field");
		VBox nameCard = new VBox(8, caption, name);
		nameCard.getStyleClass().add("signin-code-card");
		nameCard.setAlignment(Pos.CENTER_LEFT);

		Label error = new Label();
		error.getStyleClass().add("signin-sub");
		error.setStyle("-fx-text-fill: -velo-danger;");
		error.setWrapText(true);
		error.setAlignment(Pos.CENTER);
		error.setVisible(false);
		error.setManaged(false);

		Button go = new Button("Play offline");
		go.getStyleClass().add("primary-button");
		go.setDefaultButton(true);
		go.setMaxWidth(Double.MAX_VALUE);
		go.setOnAction(e -> {
			OfflineAuth.Result result = OfflineAuth.validate(name.getText());
			if (!result.ok()) {
				error.setText(result.error());
				error.setVisible(true);
				error.setManaged(true);
				pop(error);
				return;
			}
			onContinue.accept(OfflineAuth.createSession(result.username()));
		});

		Button back = new Button("Sign in with Microsoft instead");
		back.getStyleClass().add("ghost-button");
		back.setMaxWidth(Double.MAX_VALUE);
		back.setOnAction(e -> backToMicrosoft.run());
		HBox.setHgrow(go, javafx.scene.layout.Priority.ALWAYS);
		HBox actions = new HBox(10, back, go);
		actions.setAlignment(Pos.CENTER);

		Label note = new Label("Works on singleplayer, LAN and servers with online-mode off. Velo Network extras (friends, "
				+ "parties, cosmetics sync) and premium-only servers need a Microsoft account.");
		note.getStyleClass().add("signin-note");
		note.setWrapText(true);

		name.setOnAction(e -> go.fire());
		panel.getChildren().addAll(heading, sub, nameCard, error, actions, note);
		return panel;
	}

	/** A quick scale "pop" to draw the eye to something that just changed. */
	private static void pop(javafx.scene.Node node) {
		javafx.animation.ScaleTransition up = new javafx.animation.ScaleTransition(javafx.util.Duration.millis(120), node);
		up.setFromX(1);
		up.setFromY(1);
		up.setToX(1.06);
		up.setToY(1.06);
		up.setAutoReverse(true);
		up.setCycleCount(2);
		up.play();
	}

	/**
	 * Tries several ways to open a URL, since AWT's {@code Desktop} is often
	 * unsupported/misconfigured on Linux even when a real browser is
	 * installed. Falls back to shelling out to the OS's own "open a URL"
	 * command, and only silently gives up after all of them fail (the code
	 * is still shown/copyable either way).
	 */
	private static boolean openInBrowser(String url) {
		return tryOsCommand(url) || tryAwtDesktop(url);
	}

	private static boolean tryAwtDesktop(String url) {
		try {
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
				Desktop.getDesktop().browse(URI.create(url));
				return true;
			}
		} catch (Throwable ignored) {
			// Nothing else left to try.
		}
		return false;
	}

	private static boolean tryOsCommand(String url) {
		String os = System.getProperty("os.name", "").toLowerCase();
		String[] command;
		if (os.contains("win")) {
			command = new String[] {"rundll32", "url.dll,FileProtocolHandler", url};
		} else if (os.contains("mac")) {
			command = new String[] {"open", url};
		} else {
			command = new String[] {"xdg-open", url};
		}
		try {
			new ProcessBuilder(command).start();
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	private static void copyToClipboard(String text) {
		try {
			ClipboardContent content = new ClipboardContent();
			content.putString(text);
			Clipboard.getSystemClipboard().setContent(content);
		} catch (RuntimeException e) {
			// Another app holding the Windows clipboard - the code is still shown big.
			net.veloclient.launcher.LauncherLog.warn("Clipboard", e);
		}
	}
}
