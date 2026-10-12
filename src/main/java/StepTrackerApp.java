import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;


public final class StepTrackerApp extends JFrame {
    private static Color INK = new Color(28, 48, 40);
    private static Color MUTED = new Color(113, 130, 119);
    private static Color GREEN = new Color(66, 121, 85);
    private static Color PAPER = new Color(248, 248, 241);
    private static Color CARD = Color.WHITE;
    private static Color ACTIVITY_SURFACE = new Color(239, 245, 238);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, MMM d");

    private final TrackerApiClient api = new TrackerApiClient();
    private final StepCountCircle stepCountCircle = new StepCountCircle();
    private final JPanel history = new JPanel();
    private final WeeklyChart weeklyChart = new WeeklyChart();
    private final AtomicBoolean backgroundRefreshRunning = new AtomicBoolean();
    private final Timer refreshTimer = new Timer(30_000, event -> refreshInBackground());
    private boolean darkMode;
    private boolean startupCancelled;

    private StepTrackerApp() {
        super("Stepwise | Daily steps");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(500, 650));
        setSize(560, 740);
        setLocationRelativeTo(null);
        getContentPane().setBackground(PAPER);
        setContentPane(buildScreen());
        applyThemeToWindows();
        if (!authenticateUser()) {
            startupCancelled = true;
            dispose();
            return;
        }
        refresh();
        refreshTimer.start();
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent event) { refreshTimer.stop(); }
        });
    }

    private JPanel buildScreen() {
        JPanel page = new JPanel(new BorderLayout(0, 22));
        page.setName("page");
        page.setBackground(PAPER);
        page.setBorder(new EmptyBorder(30, 34, 30, 34));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        JLabel brand = new JLabel("STEPWISE");
        brand.setName("accent");
        brand.setFont(new Font("SansSerif", Font.BOLD, 14));
        brand.setForeground(GREEN);
        JLabel date = new JLabel(LocalDate.now().format(DAY));
        date.setName("muted");
        date.setFont(new Font("SansSerif", Font.PLAIN, 14));
        date.setForeground(MUTED);
        JButton retry = new JButton("Retry");
        retry.setFont(new Font("SansSerif", Font.BOLD, 12));
        retry.setForeground(GREEN);
        retry.setBorderPainted(false);
        retry.setContentAreaFilled(false);
        retry.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        retry.setName("link");
        retry.addActionListener(e -> refresh());
        JButton historyButton = new JButton("History");
        historyButton.setFont(new Font("SansSerif", Font.BOLD, 12));
        historyButton.setForeground(GREEN);
        historyButton.setBorderPainted(false);
        historyButton.setContentAreaFilled(false);
        historyButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        historyButton.setName("link");
        historyButton.addActionListener(e -> showHistory());
        JButton signOut = new JButton("Sign out");
        signOut.setFont(new Font("SansSerif", Font.BOLD, 12));
        signOut.setForeground(GREEN);
        signOut.setBorderPainted(false);
        signOut.setContentAreaFilled(false);
        signOut.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        signOut.setName("link");
        signOut.addActionListener(e -> signOut());
        JToggleButton modeSwitch = new JToggleButton("☾");
        modeSwitch.setName("mode-toggle");
        modeSwitch.setFont(new Font("SansSerif", Font.BOLD, 16));
        modeSwitch.setToolTipText("Switch to dark mode");
        modeSwitch.setFocusPainted(false);
        modeSwitch.addActionListener(e -> {
            darkMode = modeSwitch.isSelected();
            modeSwitch.setText(darkMode ? "☀" : "☾");
            modeSwitch.setToolTipText(darkMode ? "Switch to light mode" : "Switch to dark mode");
            applyThemeToWindows();
        });
        JPanel headerActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        headerActions.setOpaque(false);
        headerActions.add(retry);
        headerActions.add(historyButton);
        headerActions.add(signOut);
        headerActions.add(modeSwitch);
        headerActions.add(date);
        header.add(brand, BorderLayout.WEST);
        header.add(headerActions, BorderLayout.EAST);
        page.add(header, BorderLayout.NORTH);

        JPanel body = new ScrollablePanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.add(summaryCard());
        JScrollPane scroll = new JScrollPane(body);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        page.add(scroll, BorderLayout.CENTER);
        return page;
    }

    private void showHistory() {
        JDialog dialog = new JDialog(this, "Stepwise | Last 7 days", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout());
        content.setName("page");
        content.setBackground(PAPER);
        content.setBorder(new EmptyBorder(20, 20, 20, 20));
        content.add(historyCard(), BorderLayout.CENTER);
        dialog.setContentPane(content);
        dialog.setMinimumSize(new Dimension(460, 540));
        dialog.setSize(500, 600);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private boolean authenticateUser() {
        try {
            api.currentUser();
            return true;
        } catch (TrackerApiClient.ApiException exception) {
            if (exception.statusCode() != 401) showApiError(exception);
        } catch (Exception exception) {
            showApiError(exception);
            return false;
        }

        Object[] modes = {"Sign in", "Create account", "Cancel"};
        while (true) {
            int mode = JOptionPane.showOptionDialog(this,
                    "Sign in to access your personal Stepwise data.", "Stepwise account",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, modes, modes[0]);
            if (mode == 2 || mode == JOptionPane.CLOSED_OPTION) return false;
            boolean createAccount = mode == 1;
            JTextField email = new JTextField(24);
            JPasswordField password = new JPasswordField(24);
            JPasswordField confirmation = new JPasswordField(24);
            JPanel form = new JPanel(new GridLayout(createAccount ? 3 : 2, 1, 0, 5));
            form.add(labeledField("Email", email));
            form.add(labeledField("Password", password));
            if (createAccount) form.add(labeledField("Confirm password", confirmation));
            int result = JOptionPane.showConfirmDialog(this, form,
                    createAccount ? "Create Stepwise account" : "Sign in to Stepwise",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) continue;
            char[] passwordChars = password.getPassword();
            char[] confirmChars = confirmation.getPassword();
            try {
                if (createAccount && !java.util.Arrays.equals(passwordChars, confirmChars)) {
                    throw new IllegalArgumentException("The passwords do not match.");
                }
                if (createAccount && passwordChars.length < 12) {
                    throw new IllegalArgumentException("Use a password with at least 12 characters.");
                }
                if (createAccount) api.signUp(email.getText().trim(), new String(passwordChars));
                else api.signIn(email.getText().trim(), new String(passwordChars));
                return true;
            } catch (Exception exception) {
                JOptionPane.showMessageDialog(this, exception.getMessage(), "Account sign-in failed", JOptionPane.WARNING_MESSAGE);
            } finally {
                java.util.Arrays.fill(passwordChars, '\0');
                java.util.Arrays.fill(confirmChars, '\0');
            }
        }
    }

    private JPanel labeledField(String label, JComponent field) {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.add(new JLabel(label), BorderLayout.WEST);
        panel.add(field, BorderLayout.CENTER);
        return panel;
    }

    private void signOut() {
        try {
            api.signOut();
            refreshTimer.stop();
            dispose();
        } catch (Exception exception) {
            showApiError(exception);
        }
    }

    private void applyThemeToWindows() {
        INK = darkMode ? new Color(232, 239, 234) : new Color(28, 48, 40);
        MUTED = darkMode ? new Color(163, 179, 168) : new Color(113, 130, 119);
        GREEN = darkMode ? new Color(132, 198, 145) : new Color(66, 121, 85);
        PAPER = darkMode ? new Color(20, 29, 25) : new Color(248, 248, 241);
        CARD = darkMode ? new Color(31, 43, 37) : Color.WHITE;
        ACTIVITY_SURFACE = darkMode ? new Color(42, 58, 48) : new Color(239, 245, 238);
        for (Window window : Window.getWindows()) {
            if (window.isDisplayable()) applyTheme(window);
        }
    }

    private void applyTheme(Component component) {
        String role = component.getName();
        if (component instanceof Window window) window.setBackground(PAPER);
        if (component instanceof JPanel panel && panel.isOpaque()) {
            panel.setBackground("card".equals(role) ? CARD : PAPER);
        }
        if (component instanceof JLabel label) {
            label.setForeground("muted".equals(role) ? MUTED : "accent".equals(role) ? GREEN : INK);
        }
        if (component instanceof AbstractButton button) {
            switch (role == null ? "" : role) {
                case "activity" -> {
                    button.setForeground(GREEN);
                    button.setBackground(ACTIVITY_SURFACE);
                    button.setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(darkMode ? new Color(79, 112, 87) : new Color(188, 211, 191), 1),
                            new EmptyBorder(11, 8, 11, 8)));
                }
                case "primary-action" -> {
                    button.setForeground(Color.WHITE);
                    button.setBackground(darkMode ? new Color(54, 112, 72) : new Color(66, 121, 85));
                }
                case "mode-toggle" -> {
                    button.setForeground(GREEN);
                    button.setBackground(CARD);
                    button.setOpaque(true);
                }
                default -> button.setForeground(GREEN);
            }
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) applyTheme(child);
        }
    }

    private JPanel summaryCard() {
        JPanel card = new JPanel(new BorderLayout(0, 18));
        card.setBackground(CARD);
        card.setName("card");
        card.setBorder(new EmptyBorder(26, 26, 25, 26));

        JLabel title = new JLabel("Your daily movement");
        title.setFont(new Font("SansSerif", Font.BOLD, 21));
        title.setForeground(INK);
        title.setName("primary");
        title.setHorizontalAlignment(SwingConstants.CENTER);
        card.add(title, BorderLayout.NORTH);

        JPanel content = new JPanel(new GridBagLayout());
        content.setOpaque(false);
        GridBagConstraints row = new GridBagConstraints();
        row.gridx = 0;
        row.weightx = 1;
        row.anchor = GridBagConstraints.CENTER;
        row.insets = new Insets(4, 0, 18, 0);
        content.add(stepCountCircle, row);
        content.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent event) {
                stepCountCircle.setDiameter(Math.max(150, Math.min(260, content.getWidth() - 32)));
            }
        });

        JPanel activityButtons = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        activityButtons.setOpaque(false);
        activityButtons.add(activityButton("Walk", "walking"));
        activityButtons.add(activityButton("Running", "running"));
        activityButtons.add(activityButton("Cycling", "cycling"));
        row.gridy = 1;
        row.insets = new Insets(4, 0, 18, 0);
        content.add(activityButtons, row);

        JButton add = new JButton("＋  Log steps");
        add.setFont(new Font("SansSerif", Font.BOLD, 15));
        add.setForeground(Color.WHITE);
        add.setBackground(GREEN);
        add.setName("primary-action");
        add.setFocusPainted(false);
        add.setBorder(new EmptyBorder(13, 18, 13, 18));
        add.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        add.addActionListener(e -> addSteps());
        row.gridy = 2;
        row.insets = new Insets(4, 0, 8, 0);
        content.add(add, row);
        card.add(content, BorderLayout.CENTER);
        return card;
    }

    private JButton activityButton(String label, String activity) {
        JButton button = new JButton(label);
        button.setFont(new Font("SansSerif", Font.BOLD, 13));
        button.setForeground(GREEN);
        button.setBackground(ACTIVITY_SURFACE);
        button.setName("activity");
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(188, 211, 191), 1),
                new EmptyBorder(11, 8, 11, 8)));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setMargin(new Insets(8, 12, 8, 12));
        button.addActionListener(event -> {
            if ("walking".equals(activity)) showWalkSetup();
            else logActivity(activity);
        });
        return button;
    }

    private void showWalkSetup() {
        JDialog setup = new JDialog(this, "Walking distance", true);
        setup.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setup.getContentPane().setBackground(PAPER);
        setup.setLayout(new BorderLayout(0, 18));
        setup.getRootPane().setBorder(new EmptyBorder(24, 24, 24, 24));

        JLabel title = new JLabel("Choose your walk distance", SwingConstants.CENTER);
        title.setFont(new Font("SansSerif", Font.BOLD, 20));
        title.setForeground(INK);
        setup.add(title, BorderLayout.NORTH);

        JPanel distanceCard = new JPanel(new GridLayout(1, 3, 16, 0));
        distanceCard.setName("card");
        distanceCard.setBackground(CARD);
        distanceCard.setBorder(new EmptyBorder(26, 22, 26, 22));
        JLabel distanceValue = new JLabel("5.0 km", SwingConstants.CENTER);
        distanceValue.setFont(new Font("SansSerif", Font.BOLD, 28));
        distanceValue.setForeground(INK);
        JButton minus = selectorButton("−");
        JButton plus = selectorButton("+");
        minus.setFont(new Font("SansSerif", Font.BOLD, 24));
        plus.setFont(new Font("SansSerif", Font.BOLD, 24));
        minus.setName("activity");
        plus.setName("activity");
        minus.addActionListener(event -> {
            double current = Double.parseDouble(distanceValue.getText().replace(" km", ""));
            distanceValue.setText(String.format(Locale.ROOT, "%.1f km", Math.max(1.0, current - 1.0)));
        });
        plus.addActionListener(event -> {
            double current = Double.parseDouble(distanceValue.getText().replace(" km", ""));
            distanceValue.setText(String.format(Locale.ROOT, "%.1f km", Math.min(100.0, current + 1.0)));
        });
        distanceCard.add(minus);
        distanceCard.add(distanceValue);
        distanceCard.add(plus);
        JButton enter = new JButton("Enter");
        enter.setName("primary-action");
        enter.setFont(new Font("SansSerif", Font.BOLD, 15));
        enter.setForeground(Color.WHITE);
        enter.setBackground(GREEN);
        enter.setFocusPainted(false);
        enter.setBorder(new EmptyBorder(12, 24, 12, 24));
        enter.addActionListener(event -> {
            double targetKm = Double.parseDouble(distanceValue.getText().replace(" km", ""));
            setup.dispose();
            new WalkSessionDialog(this, targetKm).setVisible(true);
        });
        JPanel selection = new JPanel();
        selection.setOpaque(false);
        selection.setLayout(new BoxLayout(selection, BoxLayout.Y_AXIS));
        distanceCard.setAlignmentX(Component.CENTER_ALIGNMENT);
        distanceCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 132));
        selection.add(distanceCard);
        selection.add(Box.createVerticalStrut(18));
        enter.setAlignmentX(Component.CENTER_ALIGNMENT);
        selection.add(enter);
        setup.add(selection, BorderLayout.CENTER);
        setup.setMinimumSize(new Dimension(500, 650));
        setup.setSize(getSize());
        setup.setLocationRelativeTo(this);
        setup.setVisible(true);
    }

    private JButton selectorButton(String label) {
        JButton button = new JButton(label);
        button.setName("activity");
        button.setForeground(GREEN);
        button.setBackground(ACTIVITY_SURFACE);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(darkMode ? new Color(79, 112, 87) : new Color(188, 211, 191), 1),
                new EmptyBorder(8, 12, 8, 12)));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private void logActivity(String activity) {
        String input = JOptionPane.showInputDialog(this,
                "How many steps did you log for " + activity + "?", "Log " + activity, JOptionPane.PLAIN_MESSAGE);
        if (input == null) return;
        try {
            long amount = Long.parseLong(input.trim().replace(",", ""));
            if (amount <= 0 || amount > 1_000_000) throw new NumberFormatException();
            refresh(api.addSteps(amount, activity));
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Enter a whole number between 1 and 1,000,000.", "Invalid step count", JOptionPane.WARNING_MESSAGE);
        } catch (Exception ex) {
            showApiError(ex);
        }
    }

    private JPanel historyCard() {
        JPanel card = new JPanel(new BorderLayout(0, 16));
        card.setBackground(CARD);
        card.setName("card");
        card.setBorder(new EmptyBorder(22, 26, 22, 26));
        JLabel title = new JLabel("Last 7 days");
        title.setFont(new Font("SansSerif", Font.BOLD, 17));
        title.setForeground(INK);
        title.setName("primary");
        card.add(title, BorderLayout.NORTH);
        history.setOpaque(false);
        history.setLayout(new BoxLayout(history, BoxLayout.Y_AXIS));
        weeklyChart.setPreferredSize(new Dimension(440, 180));
        weeklyChart.setMinimumSize(new Dimension(280, 160));
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(weeklyChart);
        content.add(Box.createVerticalStrut(12));
        content.add(history);
        card.add(content, BorderLayout.CENTER);
        return card;
    }

    private void addSteps() {
        String input = JOptionPane.showInputDialog(this, "How many steps would you like to add?", "Log steps", JOptionPane.PLAIN_MESSAGE);
        if (input == null) return;
        try {
            long amount = Long.parseLong(input.trim().replace(",", ""));
            if (amount <= 0 || amount > 1_000_000) throw new NumberFormatException();
            refresh(api.addSteps(amount));
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Enter a whole number between 1 and 1,000,000.", "Invalid step count", JOptionPane.WARNING_MESSAGE);
        } catch (Exception ex) {
            showApiError(ex);
        }
    }

    private void refresh() {
        try { refresh(api.summary()); }
        catch (Exception ex) { showApiError(ex); }
    }

    private void refreshInBackground() {
        if (!backgroundRefreshRunning.compareAndSet(false, true)) return;
        new SwingWorker<TrackerApiClient.Snapshot, Void>() {
            @Override protected TrackerApiClient.Snapshot doInBackground() throws Exception {
                return api.summary();
            }
            @Override protected void done() {
                backgroundRefreshRunning.set(false);
                try {
                    refresh(get());
                } catch (Exception ex) {
                    stepCountCircle.repaint();
                }
            }
        }.execute();
    }

    private void refresh(TrackerApiClient.Snapshot snapshot) {
        long today = snapshot.steps();
        stepCountCircle.setSteps(today);
        weeklyChart.setData(snapshot.history());
        history.removeAll();
        for (int i = 0; i < snapshot.history().size(); i++) {
            TrackerApiClient.Day day = snapshot.history().get(i);
            history.add(historyRow(day.date(), day.steps()));
            if (i < snapshot.history().size() - 1) history.add(Box.createVerticalStrut(9));
        }
        history.revalidate();
        history.repaint();
    }

    private JPanel historyRow(LocalDate day, long count) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        JLabel label = new JLabel(day.equals(LocalDate.now()) ? "Today" : day.format(DAY));
        label.setName("muted");
        label.setFont(new Font("SansSerif", Font.PLAIN, 13));
        label.setForeground(INK);
        JLabel value = new JLabel(String.format("%,d", count));
        value.setName(day.equals(LocalDate.now()) ? "accent" : "muted");
        value.setFont(new Font("SansSerif", Font.BOLD, 13));
        value.setForeground(day.equals(LocalDate.now()) ? GREEN : MUTED);
        row.add(label, BorderLayout.WEST);
        row.add(value, BorderLayout.EAST);
        return row;
    }

    private void showApiError(Exception ex) {
        if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
        if (history.getComponentCount() == 0) {
            JLabel empty = new JLabel("Start the StepTracker API to load your steps.");
            empty.setFont(new Font("SansSerif", Font.PLAIN, 13));
            empty.setForeground(MUTED);
            history.add(empty);
        }
        history.revalidate();
        history.repaint();
        JOptionPane.showMessageDialog(this,
                "The StepTracker API isn't running at " + System.getenv().getOrDefault("STEPTRACKER_API", "http://localhost:8080")
                        + ".\n\nFrom the project directory, run:\n./scripts/run-api.sh\n\nKeep that terminal open, then retry in the app.\n\n" + ex.getMessage(),
                "API connection error", JOptionPane.ERROR_MESSAGE);
    }

    private final class WalkSessionDialog extends JDialog {
        private final double targetKm;
        private final WalkProgressCard progressCard;
        private final JLabel paceValue = new JLabel("— min/km", SwingConstants.CENTER);
        private final JLabel elapsedValue = new JLabel("Elapsed 00:00", SwingConstants.CENTER);
        private final JButton pauseButton = new JButton("Pause");
        private final Timer sessionTimer;
        private double distanceKm;
        private long startedAt = System.nanoTime();
        private long pausedAt;
        private long pausedNanos;
        private boolean running = true;

        private WalkSessionDialog(JFrame owner, double targetKm) {
            super(owner, "Walk session", true);
            this.targetKm = targetKm;
            progressCard = new WalkProgressCard(targetKm);
            setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

            JPanel page = new JPanel(new BorderLayout(0, 16));
            page.setName("page");
            page.setBackground(PAPER);
            page.setBorder(new EmptyBorder(24, 28, 24, 28));
            JLabel title = new JLabel("Your walk", SwingConstants.CENTER);
            title.setFont(new Font("SansSerif", Font.BOLD, 22));
            title.setForeground(INK);
            page.add(title, BorderLayout.NORTH);

            JPanel sections = new JPanel();
            sections.setOpaque(false);
            sections.setLayout(new BoxLayout(sections, BoxLayout.Y_AXIS));
            progressCard.setAlignmentX(Component.CENTER_ALIGNMENT);
            sections.add(progressCard);
            JLabel targetLabel = new JLabel(String.format(Locale.ROOT, "Target distance: %.1f km", targetKm), SwingConstants.CENTER);
            targetLabel.setForeground(MUTED);
            targetLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            sections.add(Box.createVerticalStrut(8));
            sections.add(targetLabel);
            sections.add(Box.createVerticalStrut(14));

            JPanel distanceControls = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
            distanceControls.setOpaque(false);
            JButton minusDistance = selectorButton("− 0.1 km");
            JButton enterDistance = selectorButton("Enter distance");
            JButton plusDistance = selectorButton("+ 0.1 km");
            minusDistance.addActionListener(event -> changeDistance(-0.1));
            plusDistance.addActionListener(event -> changeDistance(0.1));
            enterDistance.addActionListener(event -> enterDistance());
            distanceControls.add(minusDistance);
            distanceControls.add(enterDistance);
            distanceControls.add(plusDistance);
            sections.add(distanceControls);
            sections.add(Box.createVerticalStrut(20));

            JPanel paceCard = new JPanel();
            paceCard.setName("card");
            paceCard.setBackground(CARD);
            paceCard.setBorder(new EmptyBorder(18, 20, 18, 20));
            paceCard.setLayout(new BoxLayout(paceCard, BoxLayout.Y_AXIS));
            JLabel paceTitle = new JLabel("Walking speed", SwingConstants.CENTER);
            paceTitle.setFont(new Font("SansSerif", Font.BOLD, 15));
            paceTitle.setForeground(INK);
            paceTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
            paceValue.setFont(new Font("SansSerif", Font.BOLD, 25));
            paceValue.setForeground(GREEN);
            paceValue.setAlignmentX(Component.CENTER_ALIGNMENT);
            elapsedValue.setForeground(MUTED);
            elapsedValue.setAlignmentX(Component.CENTER_ALIGNMENT);
            paceCard.add(paceTitle);
            paceCard.add(Box.createVerticalStrut(8));
            paceCard.add(paceValue);
            paceCard.add(Box.createVerticalStrut(5));
            paceCard.add(elapsedValue);
            sections.add(paceCard);
            page.add(sections, BorderLayout.CENTER);

            JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
            actions.setOpaque(false);
            pauseButton.setName("activity");
            pauseButton.setForeground(GREEN);
            pauseButton.setBackground(ACTIVITY_SURFACE);
            pauseButton.setOpaque(true);
            pauseButton.setFocusPainted(false);
            pauseButton.setBorder(new EmptyBorder(10, 18, 10, 18));
            pauseButton.addActionListener(event -> togglePause());
            JButton finish = new JButton("Finish walk");
            finish.setName("primary-action");
            finish.setFont(new Font("SansSerif", Font.BOLD, 13));
            finish.setForeground(Color.WHITE);
            finish.setBackground(GREEN);
            finish.setFocusPainted(false);
            finish.setBorder(new EmptyBorder(10, 18, 10, 18));
            finish.addActionListener(event -> dispose());
            actions.add(pauseButton);
            actions.add(finish);
            page.add(actions, BorderLayout.SOUTH);

            setContentPane(page);
            setSize(owner.getSize());
            setMinimumSize(owner.getMinimumSize());
            setLocationRelativeTo(owner);
            sessionTimer = new Timer(1000, event -> refreshMetrics());
            sessionTimer.start();
            addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosed(java.awt.event.WindowEvent event) { sessionTimer.stop(); }
            });
        }

        private void changeDistance(double delta) {
            distanceKm = Math.max(0, Math.round((distanceKm + delta) * 10.0) / 10.0);
            progressCard.setDistance(distanceKm);
            refreshMetrics();
        }

        private void enterDistance() {
            String input = JOptionPane.showInputDialog(this, "Total distance covered in km:",
                    String.format(Locale.ROOT, "%.1f", distanceKm));
            if (input == null) return;
            try {
                double value = Double.parseDouble(input.trim());
                if (!Double.isFinite(value) || value < 0 || value > 1000) throw new NumberFormatException();
                distanceKm = Math.round(value * 10.0) / 10.0;
                progressCard.setDistance(distanceKm);
                refreshMetrics();
            } catch (NumberFormatException exception) {
                JOptionPane.showMessageDialog(this, "Enter a distance between 0 and 1,000 km.",
                        "Invalid distance", JOptionPane.WARNING_MESSAGE);
            }
        }

        private void togglePause() {
            if (running) {
                pausedAt = System.nanoTime();
                running = false;
                sessionTimer.stop();
                pauseButton.setText("Resume");
            } else {
                pausedNanos += System.nanoTime() - pausedAt;
                running = true;
                sessionTimer.start();
                pauseButton.setText("Pause");
            }
            refreshMetrics();
        }

        private void refreshMetrics() {
            long now = running ? System.nanoTime() : pausedAt;
            long elapsedNanos = Math.max(0, now - startedAt - pausedNanos);
            long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(elapsedNanos);
            elapsedValue.setText(String.format(Locale.ROOT, "Elapsed %02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60));
            paceValue.setText(distanceKm > 0
                    ? String.format(Locale.ROOT, "%.1f min/km", elapsedNanos / 60_000_000_000.0 / distanceKm)
                    : "— min/km");
        }
    }

    private final class WalkProgressCard extends JComponent {
        private static final Color BLUE = new Color(42, 119, 226);
        private final double targetKm;
        private double distanceKm;

        private WalkProgressCard(double targetKm) {
            this.targetKm = targetKm;
            setPreferredSize(new Dimension(460, 190));
            setMinimumSize(new Dimension(280, 170));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 190));
        }

        private void setDistance(double value) {
            distanceKm = value;
            repaint();
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double left = 5, top = 5, width = getWidth() - 10, height = getHeight() - 10;
            g.setColor(CARD);
            g.fill(new RoundRectangle2D.Double(left, top, width, height, 14, 14));
            g.setColor(darkMode ? new Color(69, 87, 79) : new Color(204, 214, 218));
            g.setStroke(new BasicStroke(5, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            g.draw(new java.awt.geom.Rectangle2D.Double(left + 3, top + 3, width - 6, height - 6));

            double progress = Math.min(1.0, Math.max(0.0, distanceKm / targetKm));
            double x = left + 3, y = top + 3, w = width - 6, h = height - 6;
            double[] lengths = {w, h, w, h};
            double[][] segments = {{x, y, x + w, y}, {x + w, y, x + w, y + h},
                    {x + w, y + h, x, y + h}, {x, y + h, x, y}};
            double remaining = progress * 2 * (w + h);
            java.awt.geom.Path2D.Double path = new java.awt.geom.Path2D.Double();
            path.moveTo(x, y);
            for (int i = 0; i < lengths.length && remaining > 0; i++) {
                double used = Math.min(lengths[i], remaining);
                double ratio = used / lengths[i];
                path.lineTo(segments[i][0] + (segments[i][2] - segments[i][0]) * ratio,
                        segments[i][1] + (segments[i][3] - segments[i][1]) * ratio);
                remaining -= used;
            }
            g.setColor(BLUE);
            g.setStroke(new BasicStroke(5, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            g.draw(path);

            String distance = String.format(Locale.ROOT, "%.1f km", distanceKm);
            g.setFont(new Font("SansSerif", Font.BOLD, 34));
            g.setColor(INK);
            FontMetrics valueMetrics = g.getFontMetrics();
            g.drawString(distance, (getWidth() - valueMetrics.stringWidth(distance)) / 2, getHeight() / 2 + 5);
            String caption = "DISTANCE COVERED";
            g.setFont(new Font("SansSerif", Font.BOLD, 10));
            g.setColor(MUTED);
            FontMetrics captionMetrics = g.getFontMetrics();
            g.drawString(caption, (getWidth() - captionMetrics.stringWidth(caption)) / 2, getHeight() / 2 + 29);
            g.dispose();
        }
    }

    private static final class WeeklyChart extends JPanel {
        private List<TrackerApiClient.Day> days = List.of();

        private WeeklyChart() {
            setOpaque(false);
        }

        private void setData(List<TrackerApiClient.Day> source) {
            days = new ArrayList<>(source);
            days.sort(Comparator.comparing(TrackerApiClient.Day::date));
            repaint();
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (days.isEmpty()) return;
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setFont(new Font("SansSerif", Font.PLAIN, 11));
            int left = 8, right = 8, top = 20, bottom = 26;
            int chartHeight = Math.max(40, getHeight() - top - bottom);
            int chartWidth = Math.max(1, getWidth() - left - right);
            long max = days.stream().mapToLong(TrackerApiClient.Day::steps).max().orElse(0);
            double scale = max == 0 ? 1 : max * 1.15;
            int slot = chartWidth / days.size();
            int barWidth = Math.max(12, Math.min(34, slot - 12));
            for (int i = 0; i < days.size(); i++) {
                TrackerApiClient.Day day = days.get(i);
                int x = left + i * slot + (slot - barWidth) / 2;
                int height = (int) Math.round(day.steps() / scale * chartHeight);
                int y = top + chartHeight - height;
                g.setColor(i == days.size() - 1 ? GREEN : new Color(166, 194, 168));
                g.fill(new RoundRectangle2D.Double(x, y, barWidth, Math.max(4, height), 10, 10));
                g.setColor(MUTED);
                String label = day.date().format(DateTimeFormatter.ofPattern("E"));
                FontMetrics fm = g.getFontMetrics();
                g.drawString(label, x + (barWidth - fm.stringWidth(label)) / 2, getHeight() - 7);
            }
            g.dispose();
        }
    }

    private static final class ScrollablePanel extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(16, visibleRect.height - 16);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private final class StepCountCircle extends JComponent {
        private static final Color BLUE = new Color(42, 119, 226);
        private long steps;

        private StepCountCircle() {
            setDiameter(220);
            setToolTipText("Steps walked today");
        }

        private void setDiameter(int diameter) {
            if (getPreferredSize().width == diameter) return;
            Dimension size = new Dimension(diameter, diameter);
            setPreferredSize(size);
            setMinimumSize(size);
            setMaximumSize(size);
            revalidate();
        }

        private void setSteps(long stepsToday) {
            steps = stepsToday;
            repaint();
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double inset = 8;
            double diameter = Math.max(1, Math.min(getWidth(), getHeight()) - inset * 2);
            double x = (getWidth() - diameter) / 2;
            double y = (getHeight() - diameter) / 2;
            Ellipse2D circle = new Ellipse2D.Double(x, y, diameter, diameter);
            g.setColor(darkMode ? new Color(34, 49, 70) : new Color(242, 248, 255));
            g.fill(circle);
            g.setColor(BLUE);
            g.setStroke(new BasicStroke(9, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(circle);

            String count = String.format("%,d", steps);
            g.setFont(new Font("SansSerif", Font.BOLD, count.length() > 7 ? 27 : 34));
            g.setColor(INK);
            FontMetrics countMetrics = g.getFontMetrics();
            g.drawString(count, (getWidth() - countMetrics.stringWidth(count)) / 2, getHeight() / 2 + 4);

            String caption = "STEPS WALKED TODAY";
            g.setFont(new Font("SansSerif", Font.BOLD, 11));
            g.setColor(MUTED);
            FontMetrics captionMetrics = g.getFontMetrics();
            g.drawString(caption, (getWidth() - captionMetrics.stringWidth(caption)) / 2, getHeight() / 2 + 28);
            g.dispose();
        }

    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            StepTrackerApp app = new StepTrackerApp();
            if (!app.startupCancelled) app.setVisible(true);
        });
    }
}
