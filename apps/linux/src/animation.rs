//! Decorative animation runs only in an active, mapped window, at most 30 FPS.
use gtk::glib::{ControlFlow, SignalHandlerId, WeakRef};
use gtk::prelude::*;
use std::cell::RefCell;
use std::rc::Rc;

#[derive(Default)]
struct Frames {
    last: Option<i64>,
}

impl Frames {
    fn due(&mut self, now: i64) -> bool {
        if self.last.is_some_and(|last| now - last < 33_334) {
            return false;
        }
        self.last = Some(now);
        true
    }
}

#[derive(Default)]
struct Animation {
    tick: Option<gtk::TickCallbackId>,
    window: Option<(WeakRef<gtk::Window>, SignalHandlerId)>,
    settings: Option<(gtk::Settings, SignalHandlerId)>,
}

impl Animation {
    fn stop(&mut self) {
        if let Some(tick) = self.tick.take() {
            tick.remove();
        }
    }

    fn detach(&mut self) {
        self.stop();
        if let Some((window, id)) = self.window.take()
            && let Some(window) = window.upgrade()
        {
            window.disconnect(id);
        }
        if let Some((settings, id)) = self.settings.take() {
            settings.disconnect(id);
        }
    }

    fn refresh(&mut self, area: &gtk::DrawingArea) {
        if !running(area) {
            let was_running = self.tick.is_some();
            self.stop();
            if was_running {
                area.queue_draw();
            }
        } else if self.tick.is_none() {
            let frames = RefCell::new(Frames::default());
            self.tick = Some(area.add_tick_callback(move |a, clock| {
                if frames.borrow_mut().due(clock.frame_time()) {
                    a.queue_draw();
                }
                ControlFlow::Continue
            }));
        }
    }
}

pub fn running(area: &gtk::DrawingArea) -> bool {
    area.is_mapped()
        && area
            .root()
            .and_downcast::<gtk::Window>()
            .is_some_and(|w| w.is_active())
        && area.settings().is_gtk_enable_animations()
}

/// The callbacks hold only weak widgets. Unmapping removes all window/settings
/// observers, so discarded rows don't accumulate listeners on their old window.
pub fn attach(area: &gtk::DrawingArea) {
    let state = Rc::new(RefCell::new(Animation::default()));
    let mapped = state.clone();
    area.connect_map(move |area| {
        let mut animation = mapped.borrow_mut();
        animation.detach();
        if let Some(window) = area.root().and_downcast::<gtk::Window>() {
            let state = mapped.clone();
            let weak = area.downgrade();
            let id = window.connect_is_active_notify(move |_| {
                if let Some(area) = weak.upgrade() {
                    state.borrow_mut().refresh(&area);
                }
            });
            animation.window = Some((window.downgrade(), id));
        }
        let settings = area.settings();
        let state = mapped.clone();
        let weak = area.downgrade();
        let id = settings.connect_gtk_enable_animations_notify(move |_| {
            if let Some(area) = weak.upgrade() {
                state.borrow_mut().refresh(&area);
            }
        });
        animation.settings = Some((settings, id));
        animation.refresh(area);
    });
    area.connect_unmap(move |_| state.borrow_mut().detach());
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::Cell;
    use std::time::{Duration, Instant};

    #[test]
    fn limits_redraws_on_a_fast_display() {
        let mut frames = Frames::default();
        let drawn: Vec<_> = (0..120)
            .map(|i| i * 8_334)
            .filter(|&t| frames.due(t))
            .collect();
        assert_eq!(drawn.len(), 30);
        assert!(drawn.windows(2).all(|pair| pair[1] - pair[0] >= 33_334));
    }

    #[test]
    fn resuming_does_not_burst_to_catch_up() {
        let mut frames = Frames::default();
        assert!(frames.due(0));
        assert!(frames.due(5_000_000));
        assert!(!frames.due(5_008_334));
        assert!(frames.due(5_033_334));
    }

    fn pump(duration: Duration) {
        let deadline = Instant::now() + duration;
        while Instant::now() < deadline {
            while gtk::glib::MainContext::default().pending() {
                gtk::glib::MainContext::default().iteration(false);
            }
            std::thread::sleep(Duration::from_millis(5));
        }
    }

    fn active(window: &gtk::Window) {
        let deadline = Instant::now() + Duration::from_secs(3);
        while !window.is_active() && Instant::now() < deadline {
            pump(Duration::from_millis(10));
        }
        assert!(window.is_active(), "requires a running window manager");
        pump(Duration::from_millis(100));
    }

    #[test]
    #[ignore = "requires Xvfb and a window manager"]
    fn native_animation_pauses_resumes_and_detaches() {
        gtk::init().unwrap();
        let area = gtk::DrawingArea::builder()
            .content_width(40)
            .content_height(40)
            .build();
        let settings = area.settings();
        let original = settings.is_gtk_enable_animations();
        settings.set_gtk_enable_animations(true);
        let draws = Rc::new(Cell::new(0));
        let counted = draws.clone();
        area.set_draw_func(move |_, _, _, _| counted.set(counted.get() + 1));
        attach(&area);
        let window = gtk::Window::builder().child(&area).build();
        window.present();
        active(&window);
        let before = draws.get();
        pump(Duration::from_millis(500));
        let animated = draws.get() - before;
        assert!(
            (5..=17).contains(&animated),
            "redrew {animated} times in half a second"
        );

        let other = gtk::Window::new();
        other.present();
        active(&other);
        assert!(!window.is_active());
        let paused = draws.get();
        pump(Duration::from_millis(200));
        assert_eq!(draws.get(), paused, "inactive window kept redrawing");
        window.present();
        active(&window);
        pump(Duration::from_millis(200));
        assert!(draws.get() > paused);

        settings.set_gtk_enable_animations(false);
        pump(Duration::from_millis(100));
        let reduced = draws.get();
        pump(Duration::from_millis(200));
        assert_eq!(draws.get(), reduced, "disabled animations kept redrawing");
        settings.set_gtk_enable_animations(true);
        pump(Duration::from_millis(200));
        assert!(draws.get() > reduced);

        window.set_child(gtk::Widget::NONE);
        let weak = area.downgrade();
        drop(area);
        pump(Duration::from_millis(100));
        assert!(
            weak.upgrade().is_none(),
            "animation retained the removed widget"
        );
        window.close();
        other.close();
        settings.set_gtk_enable_animations(original);
    }
}
