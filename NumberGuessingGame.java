// NumberGuessingGame.java
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.*;

public class NumberGuessingGame {

    private static final int MAX_LEADERBOARD_ENTRIES = 20;
    private static final Path HIGHSCORE_FILE = Paths.get("guess_highscores.csv");
    private static final Path SETTINGS_FILE = Paths.get("guess_settings.properties");
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private enum Level {
        EASY("Easy", 1, 50, 15),
        MEDIUM("Medium", 1, 100, 30),
        HARD("Hard", 1, 500, 60);

        final String label;
        final int min, max, timeSeconds;
        Level(String label, int min, int max, int timeSeconds) { this.label = label; this.min = min; this.max = max; this.timeSeconds = timeSeconds; }
        @Override public String toString() { return label; }
    }

    private static class ScoreEntry {
        String name;
        Level level;
        int attempts;
        int timeSeconds;
        LocalDateTime date;
        int correctNumber;

        ScoreEntry(String name, Level level, int attempts, int timeSeconds, LocalDateTime date, int correctNumber) {
            this.name = name; this.level = level; this.attempts = attempts;
            this.timeSeconds = timeSeconds; this.date = date; this.correctNumber = correctNumber;
        }

        String toCsvLine() {
            return escapeCsv(name) + "," + level.name() + "," + attempts + "," + timeSeconds + "," + date.format(DF) + "," + correctNumber;
        }

        static ScoreEntry fromCsvLine(String ln) {
            String[] parts = ln.split(",");
            if(parts.length < 6) return null;
            try {
                String name = parts[0];
                Level level = Level.valueOf(parts[1]);
                int attempts = Integer.parseInt(parts[2]);
                int time = Integer.parseInt(parts[3]);
                LocalDateTime date = LocalDateTime.parse(parts[4], DF);
                int correctNumber = Integer.parseInt(parts[5]);
                return new ScoreEntry(name, level, attempts, time, date, correctNumber);
            } catch (Exception e) { return null; }
        }

        static String escapeCsv(String s) {
            if (s.contains(",") || s.contains("\"") || s.contains("\n")) return "\"" + s.replace("\"", "\"\"") + "\"";
            return s;
        }
    }

    private final java.util.List<ScoreEntry> highScores = new ArrayList<>();
    private boolean muted = false;
    private Level currentLevel = Level.MEDIUM;
    private int secretNumber = 0;
    private int attempts = 0;
    private long startTimeMillis = 0;
    private javax.swing.Timer countdownTimer;
    private int timeLeftSeconds;

    private JFrame frame;
    private JLabel lblRange, lblFeedback, lblTimer, lblAttempts;
    private JTextField guessField;
    private JButton btnGuess, btnNewGame, btnShowScores, btnSettings;
    private JComboBox<Level> levelCombo;
    private JProgressBar timeProgress;
    private JTable scoreTable;
    private DefaultTableModel scoreTableModel;
    private final Properties settings = new Properties();

    public NumberGuessingGame() {
        loadSettings();
        loadHighScores();
        SwingUtilities.invokeLater(this::createAndShowGUI);
    }

    private void loadHighScores() {
        highScores.clear();
        try {
            if (!Files.exists(HIGHSCORE_FILE)) return;
            List<String> lines = Files.readAllLines(HIGHSCORE_FILE, StandardCharsets.UTF_8);
            for (String ln : lines) {
                if (ln.trim().isEmpty() || ln.startsWith("Name")) continue;
                ScoreEntry e = ScoreEntry.fromCsvLine(ln);
                if (e != null) highScores.add(e);
            }
            sortAndPruneHighScores();
        } catch (Exception ex) { System.err.println("Failed loading highscores: " + ex.getMessage()); }
    }

    private void saveHighScores() {
        try {
            List<String> lines = new ArrayList<>();
            lines.add("Name,Level,Attempts,Time(s),Date,CorrectNumber");
            for (ScoreEntry e : highScores) lines.add(e.toCsvLine());
            Files.write(HIGHSCORE_FILE, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception ex) { System.err.println("Failed saving highscores: " + ex.getMessage()); }
    }

    private void loadSettings() {
        try {
            if (Files.exists(SETTINGS_FILE)) try (InputStream in = Files.newInputStream(SETTINGS_FILE)) { settings.load(in); }
            muted = Boolean.parseBoolean(settings.getProperty("muted", "false"));
            String lvl = settings.getProperty("lastLevel", Level.MEDIUM.name());
            try { currentLevel = Level.valueOf(lvl); } catch (Exception e) { currentLevel = Level.MEDIUM; }
        } catch (Exception ex) { System.err.println("Failed loading settings: " + ex.getMessage()); }
    }

    private void saveSettings() {
        try {
            settings.setProperty("muted", Boolean.toString(muted));
            settings.setProperty("lastLevel", currentLevel.name());
            try (OutputStream out = Files.newOutputStream(SETTINGS_FILE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                settings.store(out, "Number Guessing Game Settings");
            }
        } catch (Exception ex) { System.err.println("Failed saving settings: " + ex.getMessage()); }
    }

    private void createAndShowGUI() {
        frame = new JFrame("Number Guessing Game 🎯");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(900, 620);
        frame.setLocationRelativeTo(null);
        frame.setLayout(new BorderLayout(10, 10));

        JLabel title = new JLabel("Number Guessing Game", SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 26f));
        frame.add(title, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(8,8));
        center.setBorder(new EmptyBorder(10,10,10,10));
        frame.add(center, BorderLayout.CENTER);

        // Game panel
        JPanel gamePanel = new JPanel();
        gamePanel.setLayout(new BoxLayout(gamePanel, BoxLayout.Y_AXIS));
        gamePanel.setBorder(BorderFactory.createTitledBorder("Play"));
        center.add(gamePanel, BorderLayout.WEST);
        gamePanel.setPreferredSize(new Dimension(400, 460));

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        row.add(new JLabel("Level:"));
        levelCombo = new JComboBox<>(Level.values());
        levelCombo.setSelectedItem(currentLevel);
        levelCombo.addActionListener(e -> { currentLevel = (Level) levelCombo.getSelectedItem(); saveSettings(); updateLevelDisplay(); });
        row.add(levelCombo);

        JButton btnToggleMute = new JButton(muted ? "🔇 Muted" : "🔊 Sound On");
        btnToggleMute.addActionListener(e -> { muted = !muted; btnToggleMute.setText(muted ? "🔇 Muted" : "🔊 Sound On"); saveSettings(); });
        row.add(btnToggleMute);
        gamePanel.add(row);

        lblRange = new JLabel(); lblAttempts = new JLabel("Attempts: 0"); lblFeedback = new JLabel("Make a guess!", SwingConstants.LEFT);
        lblFeedback.setBorder(new EmptyBorder(8,0,8,0)); gamePanel.add(lblRange); gamePanel.add(lblAttempts); gamePanel.add(lblFeedback);

        JPanel guessRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        guessField = new JTextField(8); guessRow.add(new JLabel("Your guess:")); guessRow.add(guessField);
        btnGuess = new JButton("Guess"); guessRow.add(btnGuess); gamePanel.add(guessRow);

        btnNewGame = new JButton("New Game"); btnShowScores = new JButton("High Scores"); btnSettings = new JButton("Settings");
        JPanel bottomRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 10));
        bottomRow.add(btnNewGame); bottomRow.add(btnShowScores); bottomRow.add(btnSettings); gamePanel.add(bottomRow);

        JPanel timerPanel = new JPanel(new BorderLayout(6,6));
        lblTimer = new JLabel("Time left: --", SwingConstants.LEFT);
        timeProgress = new JProgressBar(0, 100); timeProgress.setStringPainted(true); timeProgress.setPreferredSize(new Dimension(220, 24));
        timerPanel.add(lblTimer, BorderLayout.WEST); timerPanel.add(timeProgress, BorderLayout.CENTER); gamePanel.add(timerPanel);

        // Leaderboard
        JPanel right = new JPanel(new BorderLayout(8,8));
        right.setBorder(BorderFactory.createTitledBorder("Leaderboard (Top " + MAX_LEADERBOARD_ENTRIES + ")"));
        right.setPreferredSize(new Dimension(460, 460)); center.add(right, BorderLayout.CENTER);

        scoreTableModel = new DefaultTableModel(new Object[]{"Name","Level","Attempts","Time(s)","Date","Correct Number"},0){
            @Override public boolean isCellEditable(int r,int c){ return false; }
        };
        scoreTable = new JTable(scoreTableModel);
        scoreTable.setFillsViewportHeight(true);
        TableRowSorter<TableModel> sorter = new TableRowSorter<>(scoreTableModel);
        sorter.setComparator(2, Comparator.comparingInt(o -> Integer.parseInt(o.toString())));
        sorter.setComparator(3, Comparator.comparingInt(o -> Integer.parseInt(o.toString())));
        scoreTable.setRowSorter(sorter);
        fillScoreTable();
        right.add(new JScrollPane(scoreTable), BorderLayout.CENTER);

        JPanel leaderControls = new JPanel(new FlowLayout(FlowLayout.LEFT,6,6));
        JButton btnExport = new JButton("Export CSV"); JButton btnImport = new JButton("Import CSV"); JButton btnClear = new JButton("Clear All");
        leaderControls.add(btnExport); leaderControls.add(btnImport); leaderControls.add(btnClear); right.add(leaderControls, BorderLayout.SOUTH);

        JLabel statusBar = new JLabel("Hint: Enter your guess then press Guess (or Enter). Good luck!");
        frame.add(statusBar, BorderLayout.SOUTH);

        btnGuess.addActionListener(e->handleGuess());
        guessField.addActionListener(e->handleGuess());
        btnNewGame.addActionListener(e->startNewGame(true));
        btnShowScores.addActionListener(e->showHighScoresDialog());
        btnSettings.addActionListener(e->showSettingsDialog());
        btnExport.addActionListener(e->exportHighScoresCsv());
        btnImport.addActionListener(e->importHighScoresCsv());
        btnClear.addActionListener(e->{
            int yn = JOptionPane.showConfirmDialog(frame,"Delete all high scores?","Confirm",JOptionPane.YES_NO_OPTION);
            if(yn==JOptionPane.YES_OPTION){ highScores.clear(); saveHighScores(); fillScoreTable(); }
        });

        updateLevelDisplay(); startNewGame(false);
        frame.setVisible(true);
    }

    private void updateLevelDisplay() { lblRange.setText(String.format("Range: %d to %d | Time: %d seconds", currentLevel.min,currentLevel.max,currentLevel.timeSeconds)); saveSettings(); }

    private void startNewGame(boolean userInitiated){
        attempts = 0; secretNumber = new Random().nextInt(currentLevel.max-currentLevel.min+1)+currentLevel.min;
        startTimeMillis = System.currentTimeMillis(); timeLeftSeconds = currentLevel.timeSeconds;
        lblAttempts.setText("Attempts: 0"); lblFeedback.setText("New number generated. Make your first guess!"); guessField.setText(""); guessField.requestFocusInWindow();
        setupTimer(); if(userInitiated && !muted) Toolkit.getDefaultToolkit().beep();
    }

    private void setupTimer(){
        if(countdownTimer!=null && countdownTimer.isRunning()) countdownTimer.stop();
        final int total = currentLevel.timeSeconds; timeProgress.setMaximum(total); timeProgress.setValue(total); timeProgress.setString(total+"s");
        lblTimer.setText("Time left: "+total+" s");

        countdownTimer = new javax.swing.Timer(1000, e->{
            timeLeftSeconds--;
            timeProgress.setValue(timeLeftSeconds); timeProgress.setString(timeLeftSeconds+"s");
            lblTimer.setText("Time left: "+timeLeftSeconds+" s");
            if(timeLeftSeconds<=0){ countdownTimer.stop(); gameOverTimeUp(); }
        });
        countdownTimer.setInitialDelay(1000); countdownTimer.start();
    }

    private void handleGuess(){
        String txt=guessField.getText().trim(); if(txt.isEmpty()) return;
        int guess;
        try{ guess=Integer.parseInt(txt); } catch(Exception ex){ lblFeedback.setText("Invalid input — enter a number."); guessField.selectAll(); return; }
        attempts++; lblAttempts.setText("Attempts: "+attempts);
        if(guess==secretNumber){
            int elapsedSec = (int)((System.currentTimeMillis()-startTimeMillis)/1000);
            if(countdownTimer!=null && countdownTimer.isRunning()) countdownTimer.stop();
            lblFeedback.setText("Correct! You guessed the number "+secretNumber+" in "+attempts+" attempts and "+elapsedSec+"s.");
            if(!muted) Toolkit.getDefaultToolkit().beep();
            promptScoreEntry(elapsedSec, true);
        } else if(guess<secretNumber){ lblFeedback.setText("Too low! Try higher."); animateShake(guessField); } 
        else { lblFeedback.setText("Too high! Try lower."); animateShake(guessField); }
        guessField.selectAll();
    }

    private void gameOverTimeUp(){ lblFeedback.setText("Time's up! Number was "+secretNumber); if(!muted) Toolkit.getDefaultToolkit().beep(); promptScoreEntry(currentLevel.timeSeconds, false); }

    private void promptScoreEntry(int timeSpent, boolean won){
        JPanel p=new JPanel(new GridLayout(0,2,6,6));
        JTextField nameField=new JTextField("Player");
        p.add(new JLabel("Name:")); p.add(nameField);
        p.add(new JLabel("Level:")); p.add(new JLabel(currentLevel.label));
        p.add(new JLabel("Attempts:")); p.add(new JLabel(String.valueOf(attempts)));
        p.add(new JLabel("Time(s):")); p.add(new JLabel(String.valueOf(timeSpent)));
        p.add(new JLabel("Date:")); p.add(new JLabel(LocalDateTime.now().format(DF)));
        p.add(new JLabel("Correct Number:")); p.add(new JLabel(String.valueOf(secretNumber)));

        JOptionPane.showMessageDialog(frame, p, won ? "You Win!" : "Game Over", JOptionPane.PLAIN_MESSAGE);
        String name=nameField.getText().trim(); if(name.isEmpty()) name="Player";
        highScores.add(new ScoreEntry(name,currentLevel,attempts,timeSpent,LocalDateTime.now(),secretNumber));
        sortAndPruneHighScores(); saveHighScores(); fillScoreTable();

        int again=JOptionPane.showConfirmDialog(frame,"Play again?","Play Again",JOptionPane.YES_NO_OPTION);
        if(again==JOptionPane.YES_OPTION) startNewGame(true);
    }

    private void sortAndPruneHighScores(){
        highScores.sort(Comparator.comparing((ScoreEntry s)->s.level.ordinal()).thenComparingInt(s->s.attempts).thenComparingInt(s->s.timeSeconds).thenComparing(s->s.date));
        while(highScores.size()>MAX_LEADERBOARD_ENTRIES) highScores.remove(highScores.size()-1);
    }

    private void fillScoreTable(){
        scoreTableModel.setRowCount(0);
        for(ScoreEntry e: highScores) scoreTableModel.addRow(new Object[]{ e.name, e.level.label, e.attempts, e.timeSeconds, e.date.format(DF), e.correctNumber });
    }

    private void showHighScoresDialog(){
        JTable table=new JTable(scoreTableModel);
        TableRowSorter<TableModel> sorter=new TableRowSorter<>(scoreTableModel);
        sorter.setComparator(2, Comparator.comparingInt(o->Integer.parseInt(o.toString())));
        sorter.setComparator(3, Comparator.comparingInt(o->Integer.parseInt(o.toString())));
        table.setRowSorter(sorter);
        JOptionPane.showMessageDialog(frame,new JScrollPane(table),"High Scores (Top "+MAX_LEADERBOARD_ENTRIES+")",JOptionPane.PLAIN_MESSAGE);
    }

    private void showSettingsDialog(){
        JPanel p=new JPanel(new GridLayout(0,2,6,6));
        JCheckBox chkMute=new JCheckBox("Mute sound",muted);
        JComboBox<Level> cbo=new JComboBox<>(Level.values());
        cbo.setSelectedItem(currentLevel);
        p.add(new JLabel("Mute sound:")); p.add(chkMute);
        p.add(new JLabel("Default level:")); p.add(cbo);

        int yn=JOptionPane.showConfirmDialog(frame,p,"Settings",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE);
        if(yn==JOptionPane.OK_OPTION){
            muted=chkMute.isSelected(); currentLevel=(Level)cbo.getSelectedItem(); levelCombo.setSelectedItem(currentLevel);
            updateLevelDisplay(); saveSettings();
        }
    }

    private void exportHighScoresCsv(){
        JFileChooser fc=new JFileChooser(); fc.setSelectedFile(new File("guess_highscores_export.csv"));
        int res=fc.showSaveDialog(frame); if(res!=JFileChooser.APPROVE_OPTION) return;
        Path out=fc.getSelectedFile().toPath();
        try{
            List<String> lines=new ArrayList<>(); lines.add("Name,Level,Attempts,Time(s),Date,CorrectNumber");
            for(ScoreEntry e: highScores) lines.add(e.toCsvLine());
            Files.write(out, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            JOptionPane.showMessageDialog(frame,"Exported to "+out.toString());
        } catch(Exception ex){ JOptionPane.showMessageDialog(frame,"Export failed: "+ex.getMessage()); }
    }

    private void importHighScoresCsv(){
        JFileChooser fc=new JFileChooser(); int res=fc.showOpenDialog(frame); if(res!=JFileChooser.APPROVE_OPTION) return;
        Path in=fc.getSelectedFile().toPath();
        try{
            List<String> lines=Files.readAllLines(in,StandardCharsets.UTF_8);
            if(lines.isEmpty()){ JOptionPane.showMessageDialog(frame,"Empty file."); return; }
            int added=0;
            for(String ln:lines){
                if(ln.trim().isEmpty()) continue;
                String low=ln.trim().toLowerCase();
                if(low.startsWith("name,") || low.startsWith("name;") || low.startsWith("id,")) continue;
                ScoreEntry e=ScoreEntry.fromCsvLine(ln);
                if(e!=null){ highScores.add(e); added++; }
            }
            if(added>0){ sortAndPruneHighScores(); saveHighScores(); fillScoreTable(); JOptionPane.showMessageDialog(frame,"Imported "+added+" entries."); }
            else JOptionPane.showMessageDialog(frame,"No valid entries found.");
        } catch(Exception ex){ JOptionPane.showMessageDialog(frame,"Import failed: "+ex.getMessage()); }
    }

    private void animateShake(JComponent comp){
        final Point orig=comp.getLocation(); final int cycles=6; final int delta=6;
        final javax.swing.Timer t=new javax.swing.Timer(20,null); final int[] i={0};
        t.addActionListener(a->{ i[0]++; int dx=(i[0]%2==0)?delta:-delta; comp.setLocation(orig.x+dx,orig.y); if(i[0]>=cycles){ t.stop(); comp.setLocation(orig); } });
        t.start();
    }

    private static String escapeCsv(String s){ return ScoreEntry.escapeCsv(s); }

    public static void main(String[] args){
        try{ UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch(Exception ignored){}
        SwingUtilities.invokeLater(NumberGuessingGame::new);
    }
}
